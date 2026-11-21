// resource-agent: CPU（コア別）・メモリ・ディスク構成/使用率を定期的にサーバーへ送るエージェント。
//
// 使い方:
//
//	resource-agent -config config.json run        # フォアグラウンドで実行（動作確認用）
//	resource-agent -config config.json once       # 1 秒間だけ測定して JSON を表示（送信しない）
//	resource-agent -config config.json install    # サービス登録（Ubuntu: systemd / Windows: サービス）
//	resource-agent start | stop | uninstall
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/kardianos/service"

	"resourcemonitor/agent/internal/buffer"
	"resourcemonitor/agent/internal/collector"
	"resourcemonitor/agent/internal/config"
	"resourcemonitor/agent/internal/sender"
)

var version = "dev" // ビルド時に -ldflags "-X main.version=..." で上書き

type program struct {
	cfg  *config.Config
	quit chan struct{}
	done chan struct{}
}

func (p *program) Start(s service.Service) error {
	p.quit = make(chan struct{})
	p.done = make(chan struct{})
	go p.loop()
	return nil
}

func (p *program) Stop(s service.Service) error {
	close(p.quit)
	select {
	case <-p.done:
	case <-time.After(15 * time.Second):
	}
	return nil
}

// 一時保存分の再送: 送信のたびに最大 flushLimit 件を、flushBatch 件ずつまとめて送る。
// 10 秒間隔・24 時間分（8,640 件）なら、30 秒ごとの送信で約 15 分で送り終わる。
const (
	flushLimit = 300
	flushBatch = 60
)

// loop は 2 つの処理を並行して動かします。
//   - 測定: sampleSeconds ごとに測定して、送信待ちの列に入れる
//   - 送信: sendSeconds ごとに、送信待ちの分をまとめて送る
//
// 送信が遅れても測定の間隔はずれません。
func (p *program) loop() {
	defer close(p.done)
	snd := sender.New(p.cfg.ServerURL, p.cfg.APIKey)
	buf, err := buffer.New(p.cfg.BufferDir, p.cfg.BufferItems())
	if err != nil {
		log.Printf("バッファを初期化できません（一時保存なしで続行）: %v", err)
	}
	sampleEvery := time.Duration(p.cfg.SampleSeconds) * time.Second
	sendEvery := time.Duration(p.cfg.SendSeconds) * time.Second
	log.Printf("resource-agent %s 開始: host=%s sample=%s send=%s server=%s",
		version, p.cfg.HostID, sampleEvery, sendEvery, p.cfg.ServerURL)

	var (
		mu      sync.Mutex
		pending [][]byte
	)
	take := func() [][]byte {
		mu.Lock()
		defer mu.Unlock()
		out := pending
		pending = nil
		return out
	}

	// 測定
	sampled := make(chan struct{})
	go func() {
		defer close(sampled)
		var sm *collector.Sampler
		ticker := time.NewTicker(sampleEvery)
		defer ticker.Stop()
		for {
			if sm == nil {
				s, err := collector.NewSampler(p.cfg.HostID)
				if err != nil {
					log.Printf("測定の準備に失敗: %v", err)
				} else {
					sm = s
				}
			}
			select {
			case <-p.quit:
				return
			case <-ticker.C:
			}
			if sm == nil {
				continue
			}
			snap, err := sm.Sample()
			if err != nil {
				log.Printf("収集失敗: %v", err)
				continue
			}
			body, _ := json.Marshal(snap)
			mu.Lock()
			pending = append(pending, body)
			mu.Unlock()
		}
	}()

	// 送信
	ticker := time.NewTicker(sendEvery)
	defer ticker.Stop()
	for {
		select {
		case <-p.quit:
			<-sampled
			// 送れなかった分は一時保存して、次に起動したときに送る
			if items := take(); len(items) > 0 && buf != nil {
				_ = buf.Push(items...)
			}
			log.Printf("resource-agent 停止")
			return
		case <-ticker.C:
			p.send(snd, buf, take())
		}
	}
}

func (p *program) send(snd *sender.Sender, buf *buffer.Buffer, items [][]byte) {
	if len(items) == 0 {
		return
	}
	if err := snd.SendBatch(items); err != nil {
		if buf != nil {
			if perr := buf.Push(items...); perr != nil {
				log.Printf("送信失敗・一時保存も失敗: %v / %v", err, perr)
			} else {
				log.Printf("送信失敗のため一時保存しました（%d 件保留中）: %v", buf.Len(), err)
			}
		} else {
			log.Printf("送信失敗: %v", err)
		}
		return
	}
	// 送信できた = サーバーが起動している。溜まっていた分を再送する
	if buf != nil {
		if n, err := buf.Flush(flushLimit, flushBatch, snd.SendBatch); n > 0 || err != nil {
			log.Printf("保留データを再送: %d 件 (err=%v)", n, err)
		}
	}
}

func main() {
	defaultCfg := filepath.Join(config.ExeDir(), "config.json")
	cfgPath := flag.String("config", defaultCfg, "設定ファイルのパス")
	flag.Parse()
	cmd := flag.Arg(0)

	absCfg, _ := filepath.Abs(*cfgPath)
	svcConfig := &service.Config{
		Name:        "resource-agent",
		DisplayName: "Resource Monitor Agent",
		Description: "CPU/メモリ/ディスクの使用状況をクラウドへ送信します。",
		Arguments:   []string{"-config", absCfg},
	}

	// 設定が不要なサービス操作
	switch cmd {
	case "start", "stop", "uninstall", "restart":
		s, err := service.New(&program{}, svcConfig)
		if err == nil {
			err = service.Control(s, cmd)
		}
		exitIf(err)
		fmt.Printf("%s: OK\n", cmd)
		return
	case "version":
		fmt.Println(version)
		return
	}

	cfg, err := config.Load(absCfg)
	exitIf(err)

	if cmd == "once" {
		sm, err := collector.NewSampler(cfg.HostID)
		exitIf(err)
		time.Sleep(time.Second)
		snap, err := sm.Sample()
		exitIf(err)
		out, _ := json.MarshalIndent(snap, "", "  ")
		fmt.Println(string(out))
		return
	}

	prg := &program{cfg: cfg}
	s, err := service.New(prg, svcConfig)
	exitIf(err)

	switch cmd {
	case "install":
		exitIf(s.Install())
		fmt.Println("install: OK（続けて start を実行してください）")
	case "", "run":
		// サービスマネージャから起動された場合も、手動実行の場合もここに来る
		exitIf(s.Run())
	default:
		exitIf(fmt.Errorf("不明なコマンド: %s", cmd))
	}
}

func exitIf(err error) {
	if err != nil {
		fmt.Fprintln(os.Stderr, "エラー:", err)
		os.Exit(1)
	}
}
