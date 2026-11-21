import type { HostSummary, Snapshot } from "./types";

async function getJson<T>(path: string): Promise<T> {
  // location.origin 基準の絶対 URL にする（http://user:pass@host/ で開いた場合も fetch できるように）
  const r = await fetch(new URL(path, location.origin), { credentials: "same-origin" });
  if (!r.ok) throw new Error(`${path} → ${r.status}`);
  return r.json() as Promise<T>;
}

export const fetchHosts = () => getJson<HostSummary[]>("/api/hosts");

export const fetchHistory = (hostId: string, minutes: number) =>
  getJson<Snapshot[]>(`/api/hosts/${encodeURIComponent(hostId)}/metrics?minutes=${minutes}`);
