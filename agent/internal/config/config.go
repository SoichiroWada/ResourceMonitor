// Package config はエージェントの設定ファイル（JSON）を読み込みます。
package config

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
)

type Config struct {
	// 送信先 URL（例: http://api.monitor.example.jp:8080/api/metrics）
	ServerURL string `json:"serverUrl"`
	// サーバー側で発行したエージェント用 API キー
	APIKey string `json:"apiKey"`
	// ホスト識別子。空ならホスト名を使用
	HostID string `json:"hostId"`
	// 収集間隔（秒）
	IntervalSeconds int `json:"intervalSeconds"`
	// 送信失敗時の一時保存先ディレクトリ。空なら実行ファイルと同じ場所の "buffer"
	BufferDir string `json:"bufferDir"`
	// 一時保存する最大件数（超えたら古いものから捨てる）
	BufferMaxItems int `json:"bufferMaxItems"`
}

// Load は path の設定を読み込み、未指定項目にデフォルト値を入れます。
func Load(path string) (*Config, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("設定ファイルを読み込めません: %w", err)
	}
	c := &Config{}
	if err := json.Unmarshal(b, c); err != nil {
		return nil, fmt.Errorf("設定ファイルの形式が不正です: %w", err)
	}
	if c.ServerURL == "" {
		return nil, errors.New("serverUrl が未設定です")
	}
	if c.APIKey == "" {
		return nil, errors.New("apiKey が未設定です")
	}
	if c.HostID == "" {
		h, _ := os.Hostname()
		c.HostID = h
	}
	if c.IntervalSeconds <= 0 {
		c.IntervalSeconds = 60
	}
	if c.BufferDir == "" {
		c.BufferDir = filepath.Join(ExeDir(), "buffer")
	}
	if c.BufferMaxItems <= 0 {
		c.BufferMaxItems = 1440 // 60 秒間隔で 24 時間分
	}
	return c, nil
}

// ExeDir は実行ファイルのあるディレクトリを返します。
func ExeDir() string {
	exe, err := os.Executable()
	if err != nil {
		return "."
	}
	return filepath.Dir(exe)
}
