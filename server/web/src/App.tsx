import { useCallback, useEffect, useMemo, useState } from "react";
import { fetchHistory, fetchHosts } from "./api";
import type { HostSummary, Snapshot } from "./types";
import { CoreGauge, GaugeDefs } from "./components/CoreGauge";
import { MemoryBar } from "./components/MemoryBar";
import { HistoryChart } from "./components/HistoryChart";
import { DiskTable } from "./components/DiskTable";

const PERIODS = [
  { minutes: 30, label: "直近 30 分" },
  { minutes: 60, label: "直近 1 時間" },
  { minutes: 180, label: "直近 3 時間" },
  { minutes: 360, label: "直近 6 時間" },
  { minutes: 720, label: "直近 12 時間" },
  { minutes: 1440, label: "直近 24 時間" },
];
const REFRESH_MS = 60_000; // 画面を開いている間は 1 分ごとに更新（＝アクセス扱いになり自動停止しない）
const STALE_MS = 5 * 60_000;

export default function App() {
  const [hosts, setHosts] = useState<HostSummary[]>([]);
  // ?host=ENIAC2 のように URL でホストを指定できる（ブックマーク用）
  const [hostId, setHostId] = useState(() => new URLSearchParams(location.search).get("host") ?? "");
  const [minutes, setMinutes] = useState(60);
  const [rows, setRows] = useState<Snapshot[]>([]);
  const [error, setError] = useState("");
  const [loaded, setLoaded] = useState(false);

  const refresh = useCallback(async () => {
    try {
      const list = await fetchHosts();
      setHosts(list);
      const id = list.some((h) => h.hostId === hostId) ? hostId : (list[0]?.hostId ?? "");
      setHostId(id);
      setRows(id ? await fetchHistory(id, minutes) : []);
      setError("");
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoaded(true);
    }
  }, [hostId, minutes]);

  useEffect(() => {
    refresh();
    const t = setInterval(refresh, REFRESH_MS);
    return () => clearInterval(t);
  }, [refresh]);

  const host = hosts.find((h) => h.hostId === hostId);
  const latest = host?.latest;

  // コアごとの、選択期間の平均使用率
  const avgs = useMemo(() => {
    const n = latest?.cpu.perCore.length ?? 0;
    return Array.from({ length: n }, (_, i) => {
      const vals = rows.map((r) => r.cpu.perCore?.[i]).filter((v): v is number => typeof v === "number");
      return vals.length ? vals.reduce((a, b) => a + b, 0) / vals.length : null;
    });
  }, [rows, latest]);

  const lastSeen = host ? new Date(host.lastSeen) : null;
  const stale = lastSeen ? Date.now() - lastSeen.getTime() > STALE_MS : false;

  return (
    <>
      <GaugeDefs />
      <header>
        <h1>Resource Monitor</h1>
        <select aria-label="ホスト" value={hostId} onChange={(e) => setHostId(e.target.value)}>
          {hosts.map((h) => <option key={h.hostId} value={h.hostId}>{h.hostId} ({h.os})</option>)}
        </select>
        <select aria-label="期間" value={minutes} onChange={(e) => setMinutes(Number(e.target.value))}>
          {PERIODS.map((p) => <option key={p.minutes} value={p.minutes}>{p.label}</option>)}
        </select>
        <button onClick={refresh}>更新</button>
      </header>

      <main>
        <section className="card meta">
          {error ? <span className="stale">読み込みに失敗しました: {error}</span>
            : !loaded ? "読み込み中…"
            : !host || !latest ? "まだデータがありません。エージェントを起動してください。"
            : <>
                <b className="host-name">{host.hostname || host.hostId}</b> ・ {host.platform} ・ {latest.cpu.model}
                {" "}・ 最終受信 <span className={stale ? "stale" : ""}>{lastSeen!.toLocaleString("ja-JP")}</span>
                {stale && "（5 分以上受信なし）"}
              </>}
        </section>

        {latest && (
          <>
            <section className="card">
              <h2>
                CPU（コア別 使用率）
                <span className="meta"> 合計 {latest.cpu.totalPercent.toFixed(1)}% ・ {latest.cpu.perCore.length} 論理コア</span>
              </h2>
              <div className="gauges">
                {latest.cpu.perCore.map((v, i) => (
                  <CoreGauge key={i} index={i} count={latest.cpu.perCore.length} value={v} avg={avgs[i]} />
                ))}
              </div>
            </section>
            <MemoryBar memory={latest.memory} />
            <HistoryChart rows={rows} />
            <DiskTable disks={latest.disks ?? []} />
          </>
        )}
      </main>
    </>
  );
}
