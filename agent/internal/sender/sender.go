// Package sender はメトリクスをサーバーへ HTTP POST します。
package sender

import (
	"bytes"
	"fmt"
	"io"
	"net/http"
	"time"
)

type Sender struct {
	url    string
	apiKey string
	client *http.Client
}

func New(url, apiKey string) *Sender {
	return &Sender{url: url, apiKey: apiKey, client: &http.Client{Timeout: 10 * time.Second}}
}

// Send は JSON 1 件を送信します。2xx 以外はエラーです。
func (s *Sender) Send(body []byte) error {
	req, err := http.NewRequest(http.MethodPost, s.url, bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Api-Key", s.apiKey)

	resp, err := s.client.Do(req)
	if err != nil {
		return err // サーバー停止中（タスク 0）ならここに来る
	}
	defer resp.Body.Close()
	_, _ = io.Copy(io.Discard, resp.Body)
	if resp.StatusCode/100 != 2 {
		return fmt.Errorf("サーバーがエラーを返しました: %s", resp.Status)
	}
	return nil
}
