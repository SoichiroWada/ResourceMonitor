import {
  CategoryScale, Chart as ChartJS, Legend, LinearScale, LineElement, PointElement, Tooltip,
} from "chart.js";
import { Line } from "react-chartjs-2";
import type { Snapshot } from "../types";

ChartJS.register(CategoryScale, LinearScale, PointElement, LineElement, Legend, Tooltip);

const timeFmt = new Intl.DateTimeFormat("ja-JP", { hour: "2-digit", minute: "2-digit" });

export function HistoryChart({ rows }: { rows: Snapshot[] }) {
  const data = {
    labels: rows.map((r) => timeFmt.format(new Date(r.timestamp))),
    datasets: [
      { label: "CPU 合計", data: rows.map((r) => r.cpu.totalPercent), borderColor: "#2563eb",
        backgroundColor: "#2563eb", pointRadius: 0, borderWidth: 2, tension: 0.2 },
      { label: "メモリ", data: rows.map((r) => r.memory.usedPercent), borderColor: "#16a34a",
        backgroundColor: "#16a34a", pointRadius: 0, borderWidth: 2, tension: 0.2 },
    ],
  };
  return (
    <section className="card">
      <h2>CPU 合計 / メモリ 使用率の推移 (%)</h2>
      <div className="chart-box">
        <Line data={data} options={{
          animation: false,
          maintainAspectRatio: false,
          interaction: { mode: "index", intersect: false },
          scales: { y: { min: 0, max: 100 }, x: { ticks: { maxTicksLimit: 16, maxRotation: 0 } } },
        }} />
      </div>
      {rows.length === 0 && <p className="meta">この期間のデータはありません。</p>}
    </section>
  );
}
