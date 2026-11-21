// Package collector は gopsutil を使って OS 共通の方法でリソース情報を収集します。
// Ubuntu / Windows どちらでも同じコードで動きます。
package collector

import (
	"runtime"
	"strings"
	"time"

	"github.com/shirou/gopsutil/v4/cpu"
	"github.com/shirou/gopsutil/v4/disk"
	"github.com/shirou/gopsutil/v4/host"
	"github.com/shirou/gopsutil/v4/mem"
)

// Snapshot はサーバーへ送る 1 回分のメトリクスです（サーバー側の JSON と対応）。
type Snapshot struct {
	HostID    string    `json:"hostId"`
	Hostname  string    `json:"hostname"`
	OS        string    `json:"os"`       // linux / windows
	Platform  string    `json:"platform"` // 例: ubuntu 26.04, Microsoft Windows 11 Pro
	Timestamp time.Time `json:"timestamp"`
	CPU       CPU       `json:"cpu"`
	Memory    Memory    `json:"memory"`
	Disks     []Disk    `json:"disks"`
}

type CPU struct {
	Model        string    `json:"model"`
	TotalPercent float64   `json:"totalPercent"`
	PerCore      []float64 `json:"perCore"` // 論理コアごとの使用率(%)
}

type Memory struct {
	TotalBytes  uint64  `json:"totalBytes"`
	UsedBytes   uint64  `json:"usedBytes"`
	UsedPercent float64 `json:"usedPercent"`
}

// Disk はパーティション（ディスク構成）と使用率です。
type Disk struct {
	Device      string  `json:"device"`     // /dev/sda1, C:
	Mountpoint  string  `json:"mountpoint"` // /, C:\
	FsType      string  `json:"fsType"`     // ext4, NTFS
	TotalBytes  uint64  `json:"totalBytes"`
	UsedBytes   uint64  `json:"usedBytes"`
	UsedPercent float64 `json:"usedPercent"`
}

// 監視対象外にするファイルシステム（snap の squashfs など）
var ignoredFsTypes = map[string]bool{
	"squashfs": true, "tmpfs": true, "devtmpfs": true, "overlay": true,
	"efivarfs": true, "fuse.snapfuse": true,
}

// Collect は sampleWindow の間 CPU を計測し、スナップショットを返します。
func Collect(hostID string, sampleWindow time.Duration) (*Snapshot, error) {
	s := &Snapshot{HostID: hostID, OS: runtime.GOOS}

	if hi, err := host.Info(); err == nil {
		s.Hostname = hi.Hostname
		s.Platform = strings.TrimSpace(hi.Platform + " " + hi.PlatformVersion)
	}

	// コアごとの使用率（sampleWindow の間ブロックして差分を取る）
	perCore, err := cpu.Percent(sampleWindow, true)
	if err != nil {
		return nil, err
	}
	s.CPU.PerCore = round(perCore)
	var sum float64
	for _, p := range perCore {
		sum += p
	}
	if len(perCore) > 0 {
		s.CPU.TotalPercent = round1(sum / float64(len(perCore)))
	}
	if infos, err := cpu.Info(); err == nil && len(infos) > 0 {
		s.CPU.Model = strings.TrimSpace(infos[0].ModelName)
	}

	vm, err := mem.VirtualMemory()
	if err != nil {
		return nil, err
	}
	s.Memory = Memory{TotalBytes: vm.Total, UsedBytes: vm.Used, UsedPercent: round1(vm.UsedPercent)}

	parts, err := disk.Partitions(false)
	if err != nil {
		return nil, err
	}
	seen := map[string]bool{}
	for _, p := range parts {
		if ignoredFsTypes[p.Fstype] || seen[p.Mountpoint] {
			continue
		}
		u, err := disk.Usage(p.Mountpoint)
		if err != nil || u.Total == 0 {
			continue // CD ドライブ等、読めないものはスキップ
		}
		seen[p.Mountpoint] = true
		s.Disks = append(s.Disks, Disk{
			Device: p.Device, Mountpoint: p.Mountpoint, FsType: p.Fstype,
			TotalBytes: u.Total, UsedBytes: u.Used, UsedPercent: round1(u.UsedPercent),
		})
	}

	s.Timestamp = time.Now().UTC()
	return s, nil
}

func round1(v float64) float64 { return float64(int64(v*10+0.5)) / 10 }

func round(vs []float64) []float64 {
	out := make([]float64, len(vs))
	for i, v := range vs {
		out[i] = round1(v)
	}
	return out
}
