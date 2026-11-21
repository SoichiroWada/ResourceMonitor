// サーバー（MetricSnapshot / HostSummary）から返る JSON の型

export interface Cpu {
  model: string;
  totalPercent: number;
  perCore: number[];
}

export interface Memory {
  totalBytes: number;
  usedBytes: number;
  usedPercent: number;
}

export interface Disk {
  device: string;
  mountpoint: string;
  fsType: string;
  totalBytes: number;
  usedBytes: number;
  usedPercent: number;
}

export interface Snapshot {
  hostId: string;
  hostname: string;
  os: string;
  platform: string;
  timestamp: string;
  cpu: Cpu;
  memory: Memory;
  disks: Disk[] | null;
}

export interface HostSummary {
  hostId: string;
  hostname: string;
  os: string;
  platform: string;
  lastSeen: string;
  latest: Snapshot;
}
