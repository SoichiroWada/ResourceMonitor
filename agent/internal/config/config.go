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
	// 測定間隔（秒）。CPU はこの間の平均、メモリとディスクは測定した時点の値
	SampleSeconds int `json:"sampleSeconds"`
	// 送信間隔（秒）。その間に測定した分をまとめて送る
	SendSeconds int `json:"sendSeconds"`
	// 送信失敗時の一時保存先ディレクトリ。空なら実行ファイルと同じ場所の "buffer"
	BufferDir string `json:"bufferDir"`
	// 一時保存する時間（時間）。超えたら古いものから捨てる
	BufferHours int `json:"bufferHours"`

	// 旧形式の項目（読み込むだけで使わない）。古い config.json でもエラーにしないため
	IntervalSeconds int `json:"intervalSeconds"`
	BufferMaxItems  int `json:"bufferMaxItems"`
}

// BufferItems は一時保存できる測定の件数です。
func (c *Config) BufferItems() int {
	return c.BufferHours * 3600 / c.SampleSeconds
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
	if c.SampleSeconds <= 0 {
		c.SampleSeconds = 10
	}
	if c.SendSeconds <= 0 {
		c.SendSeconds = 30
	}
	if c.SendSeconds < c.SampleSeconds {
		c.SendSeconds = c.SampleSeconds
	}
	if c.BufferDir == "" {
		c.BufferDir = filepath.Join(ExeDir(), "buffer")
	}
	if c.BufferHours <= 0 {
		c.BufferHours = 24
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
