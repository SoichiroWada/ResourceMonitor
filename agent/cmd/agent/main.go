// resource-agent: CPU（コア別）・メモリ・ディスク構成/使用率を定期的にサーバーへ送るエージェント。
//
// 使い方:
//
//	resource-agent -config config.json run        # フォアグラウンドで実行（動作確認用）
//	resource-agent -config config.json once       # 1 回だけ収集して JSON を表示（送信しない）
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

func (p *program) loop() {
	defer close(p.done)
	snd := sender.New(p.cfg.ServerURL, p.cfg.APIKey)
	buf, err := buffer.New(p.cfg.BufferDir, p.cfg.BufferMaxItems)
	if err != nil {
		log.Printf("バッファを初期化できません（一時保存なしで続行）: %v", err)
	}
	interval := time.Duration(p.cfg.IntervalSeconds) * time.Second
	log.Printf("resource-agent %s 開始: host=%s interval=%s server=%s", version, p.cfg.HostID, interval, p.cfg.ServerURL)

	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		p.tick(snd, buf)
		select {
		case <-p.quit:
			log.Printf("resource-agent 停止")
			return
		case <-ticker.C:
		}
	}
}

func (p *program) tick(snd *sender.Sender, buf *buffer.Buffer) {
	snap, err := collector.Collect(p.cfg.HostID, time.Second)
	if err != nil {
		log.Printf("収集失敗: %v", err)
		return
	}
	body, _ := json.Marshal(snap)

	if err := snd.Send(body); err != nil {
		if buf != nil {
			if perr := buf.Push(body); perr != nil {
				log.Printf("送信失敗・一時保存も失敗: %v / %v", err, perr)
			} else {
				log.Printf("送信失敗のため一時保存しました（%d 件保留中）: %v", buf.Len(), err)
			}
		} else {
			log.Printf("送信失敗: %v", err)
		}
		return
	}
	// 送信できた = サーバーが起動している。溜まっていた分を再送する（1 回最大 100 件）
	if buf != nil {
		if n, err := buf.Flush(100, snd.Send); n > 0 || err != nil {
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
		snap, err := collector.Collect(cfg.HostID, time.Second)
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
