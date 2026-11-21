// Package collector は gopsutil を使って OS 共通の方法でリソース情報を収集します。
// Ubuntu / Windows どちらでも同じコードで動きます。
package collector

import (
	"errors"
	"math"
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

// Sampler は CPU 使用率を「前回の測定から今回まで」の平均として求めます。
//
// OS はコアごとに CPU 時間（使用中・待機中など）の累計を数えています。前回と今回の累計の差から
// 使用率を計算するので、Sample を一定間隔で呼べば、測っていない時間のない連続した平均になります。
type Sampler struct {
	hostID   string
	prev     []cpu.TimesStat
	model    string
	hostname string
	platform string
}

// NewSampler は最初の基準値を読み込みます。最初の Sample はここからの平均になります。
func NewSampler(hostID string) (*Sampler, error) {
	s := &Sampler{hostID: hostID}
	// 変わらない情報は最初に 1 回だけ読む（Windows の cpu.Info は WMI を使うため重い）
	if hi, err := host.Info(); err == nil {
		s.hostname = hi.Hostname
		s.platform = strings.TrimSpace(hi.Platform + " " + hi.PlatformVersion)
	}
	if infos, err := cpu.Info(); err == nil && len(infos) > 0 {
		s.model = strings.TrimSpace(infos[0].ModelName)
	}
	t, err := cpu.Times(true)
	if err != nil {
		return nil, err
	}
	s.prev = t
	return s, nil
}

// Sample は前回からの CPU 平均使用率と、今の時点のメモリ・ディスクの値を返します。
func (sm *Sampler) Sample() (*Snapshot, error) {
	now, err := cpu.Times(true)
	if err != nil {
		return nil, err
	}
	s := &Snapshot{HostID: sm.hostID, OS: runtime.GOOS, Hostname: sm.hostname, Platform: sm.platform}
	s.CPU.Model = sm.model
	if len(now) == len(sm.prev) {
		perCore := make([]float64, len(now))
		var sum float64
		for i := range now {
			perCore[i] = busyPercent(sm.prev[i], now[i])
			sum += perCore[i]
		}
		s.CPU.PerCore = round(perCore)
		if len(perCore) > 0 {
			s.CPU.TotalPercent = round1(sum / float64(len(perCore)))
		}
	}
	sm.prev = now
	if len(s.CPU.PerCore) == 0 {
		// コア数が変わった直後など。基準値だけ更新して、この回は使わない
		return nil, errors.New("CPU のコア数が変わったため、この回の測定を捨てました")
	}

	vm, err := mem.VirtualMemory()
	if err != nil {
		return nil, err
	}
	s.Memory = Memory{TotalBytes: vm.Total, UsedBytes: vm.Used, UsedPercent: round1(vm.UsedPercent)}
	s.Disks = disks()
	s.Timestamp = time.Now().UTC()
	return s, nil
}

// busyPercent は 2 回の累計の差から使用率（%）を求めます（gopsutil の cpu.Percent と同じ計算）。
func busyPercent(t1, t2 cpu.TimesStat) float64 {
	all := func(t cpu.TimesStat) (total, busy float64) {
		total = t.Total()
		if runtime.GOOS == "linux" {
			total -= t.Guest + t.GuestNice // Linux では User に含まれているため
		}
		return total, total - t.Idle - t.Iowait
	}
	a1, b1 := all(t1)
	a2, b2 := all(t2)
	if b2 <= b1 {
		return 0
	}
	if a2 <= a1 {
		return 100
	}
	return math.Min(100, math.Max(0, (b2-b1)/(a2-a1)*100))
}

func disks() []Disk {
	parts, err := disk.Partitions(false)
	if err != nil {
		return nil
	}
	var out []Disk
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
		out = append(out, Disk{
			Device: p.Device, Mountpoint: p.Mountpoint, FsType: p.Fstype,
			TotalBytes: u.Total, UsedBytes: u.Used, UsedPercent: round1(u.UsedPercent),
		})
	}
	return out
}

func round1(v float64) float64 { return float64(int64(v*10+0.5)) / 10 }

func round(vs []float64) []float64 {
	out := make([]float64, len(vs))
	for i, v := range vs {
		out[i] = round1(v)
	}
	return out
}
