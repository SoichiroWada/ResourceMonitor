// Package buffer は送信できなかったメトリクスをローカルに一時保存します。
// JSON Lines 形式（1 行 = 1 件）のファイルに追記し、上限を超えたら古いものから捨てます。
package buffer

import (
	"bufio"
	"bytes"
	"os"
	"path/filepath"
	"sync"
)

type Buffer struct {
	mu       sync.Mutex
	path     string
	maxItems int
}

func New(dir string, maxItems int) (*Buffer, error) {
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return nil, err
	}
	return &Buffer{path: filepath.Join(dir, "pending.jsonl"), maxItems: maxItems}, nil
}

// Push は末尾に追加します。上限超過分は先頭（古い順）から削除します。
func (b *Buffer) Push(items ...[]byte) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	all, err := b.load()
	if err != nil {
		return err
	}
	all = append(all, items...)
	if len(all) > b.maxItems {
		all = all[len(all)-b.maxItems:]
	}
	return b.save(all)
}

// Flush は保存済みのデータを古い順に、batch 件ずつまとめて send へ渡します。
// 送信に失敗した時点で止め、未送信分をファイルに残します。最大 limit 件まで処理します。
func (b *Buffer) Flush(limit, batch int, send func([][]byte) error) (sent int, err error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	items, err := b.load()
	if err != nil || len(items) == 0 {
		return 0, err
	}
	for sent < len(items) && sent < limit {
		n := min(batch, len(items)-sent, limit-sent)
		if err = send(items[sent : sent+n]); err != nil {
			break
		}
		sent += n
	}
	if sent > 0 {
		if saveErr := b.save(items[sent:]); saveErr != nil {
			return sent, saveErr
		}
	}
	return sent, err
}

// Len は保存件数を返します。
func (b *Buffer) Len() int {
	b.mu.Lock()
	defer b.mu.Unlock()
	items, _ := b.load()
	return len(items)
}

func (b *Buffer) load() ([][]byte, error) {
	f, err := os.Open(b.path)
	if os.IsNotExist(err) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	defer f.Close()
	var items [][]byte
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 0, 64*1024), 4*1024*1024)
	for sc.Scan() {
		line := bytes.TrimSpace(sc.Bytes())
		if len(line) > 0 {
			items = append(items, append([]byte(nil), line...))
		}
	}
	return items, sc.Err()
}

// save は一時ファイルに書いてから置き換えるので、途中で落ちても壊れにくくなっています。
func (b *Buffer) save(items [][]byte) error {
	if len(items) == 0 {
		err := os.Remove(b.path)
		if os.IsNotExist(err) {
			return nil
		}
		return err
	}
	tmp := b.path + ".tmp"
	var buf bytes.Buffer
	for _, it := range items {
		buf.Write(it)
		buf.WriteByte('\n')
	}
	if err := os.WriteFile(tmp, buf.Bytes(), 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, b.path)
}
