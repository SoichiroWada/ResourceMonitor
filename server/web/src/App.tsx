import { useCallback, useEffect, useMemo, useState } from "react";
import { fetchHistory, fetchHosts } from "./api";
import type { HostSummary, Snapshot } from "./types";
import { CoreGauge, GaugeDefs } from "./components/CoreGauge";
import { CoreChart, type Point } from "./components/CoreChart";
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
// 画面を開いている間は定期的に読み直す（＝アクセス扱いになり自動停止しない）
const LATEST_MS = 30_000; // 最新値（メーター・メモリ・ディスク）は 30 秒ごと
const HISTORY_MS = 60_000; // 履歴（グラフ・期間平均）はデータ量が多いので 1 分ごと
const STALE_MS = 5 * 60_000;
type CpuView = "gauge" | "chart";
const VIEW_KEY = "rm.cpuView";

function loadView(): CpuView {
  // ?view=chart / ?view=gauge で表示方法を指定できる（指定がなければ前回の選択、初めてならグラフ）
  const q = new URLSearchParams(location.search).get("view");
  if (q === "chart" || q === "gauge") return q;
  try {
    return localStorage.getItem(VIEW_KEY) === "gauge" ? "gauge" : "chart";
  } catch {
    return "chart";
  }
}

export default function App() {
  const [hosts, setHosts] = useState<HostSummary[]>([]);
  // ?host=ENIAC2 のように URL でホストを指定できる（ブックマーク用）
  const [hostId, setHostId] = useState(() => new URLSearchParams(location.search).get("host") ?? "");
  // ?minutes=1440 のように URL で期間を指定できる（一覧にない値は 1 時間）
  const [minutes, setMinutes] = useState(() => {
    const q = Number(new URLSearchParams(location.search).get("minutes"));
    return PERIODS.some((p) => p.minutes === q) ? q : 60;
  });
  const [rows, setRows] = useState<Snapshot[]>([]);
  const [error, setError] = useState("");
  const [loaded, setLoaded] = useState(false);
  const [view, setView] = useState<CpuView>(loadView);
  const [fetchedAt, setFetchedAt] = useState(() => Date.now());

  const changeView = (v: CpuView) => {
    setView(v);
    try {
      localStorage.setItem(VIEW_KEY, v);
    } catch {
      /* 保存できなくても表示は切り替わる */
    }
  };

  // 最新値: ホスト一覧と、各ホストの最新のスナップショット
  const refreshLatest = useCallback(async () => {
    try {
      const list = await fetchHosts();
      setHosts(list);
      setHostId((cur) => (list.some((h) => h.hostId === cur) ? cur : (list[0]?.hostId ?? "")));
      setError("");
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoaded(true);
    }
  }, []);

  const host = hosts.find((h) => h.hostId === hostId);
  const hostKnown = host != null;

  // 履歴: 選択中のホストと期間の時系列
  const refreshHistory = useCallback(async () => {
    if (!hostKnown) return;
    try {
      setRows(await fetchHistory(hostId, minutes));
      setFetchedAt(Date.now());
      setError("");
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [hostId, hostKnown, minutes]);

  useEffect(() => {
    refreshLatest();
    const t = setInterval(refreshLatest, LATEST_MS);
    return () => clearInterval(t);
  }, [refreshLatest]);

  // ホストや期間を切り替えたときはすぐに読み、その後は 1 分ごと
  useEffect(() => {
    refreshHistory();
    const t = setInterval(refreshHistory, HISTORY_MS);
    return () => clearInterval(t);
  }, [refreshHistory]);

  const refreshAll = () => {
    refreshLatest();
    refreshHistory();
  };

  const latest = host?.latest;

  // コアごとの、選択期間の平均使用率
  const avgs = useMemo(() => {
    const n = latest?.cpu.perCore.length ?? 0;
    return Array.from({ length: n }, (_, i) => {
      const vals = rows.map((r) => r.cpu.perCore?.[i]).filter((v): v is number => typeof v === "number");
      return vals.length ? vals.reduce((a, b) => a + b, 0) / vals.length : null;
    });
  }, [rows, latest]);

  // コアごとの推移（グラフ表示用）
  const series = useMemo(() => {
    const n = latest?.cpu.perCore.length ?? 0;
    const out: Point[][] = Array.from({ length: n }, () => []);
    for (const r of rows) {
      const t = new Date(r.timestamp).getTime();
      r.cpu.perCore?.forEach((v, i) => {
        if (i < n && typeof v === "number") out[i].push({ t, v });
      });
    }
    return out;
  }, [rows, latest]);
  const periodLabel = PERIODS.find((p) => p.minutes === minutes)?.label.replace("直近 ", "") ?? "";

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
        <button onClick={refreshAll}>更新</button>
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
              <div className="card-head">
                <h2>
                  CPU（コア別 使用率）
                  <span className="meta"> 合計 {latest.cpu.totalPercent.toFixed(1)}% ・ {latest.cpu.perCore.length} 論理コア</span>
                </h2>
                <div className="toggle" role="group" aria-label="CPU の表示方法">
                  <button aria-pressed={view === "gauge"} onClick={() => changeView("gauge")}>メーター</button>
                  <button aria-pressed={view === "chart"} onClick={() => changeView("chart")}>グラフ</button>
                </div>
              </div>
              {view === "gauge" ? (
                <div className="gauges">
                  {latest.cpu.perCore.map((v, i) => (
                    <CoreGauge key={i} index={i} count={latest.cpu.perCore.length} value={v} avg={avgs[i]} />
                  ))}
                </div>
              ) : (
                <div className="core-charts">
                  {series.map((pts, i) => (
                    <CoreChart key={i} index={i} points={pts} from={fetchedAt - minutes * 60_000} to={fetchedAt}
                               periodLabel={periodLabel} />
                  ))}
                </div>
              )}
            </section>
            <MemoryBar memory={latest.memory} cpu={latest.cpu} />
            <HistoryChart rows={rows} />
            <DiskTable disks={latest.disks ?? []} />
          </>
        )}
      </main>
    </>
  );
}
