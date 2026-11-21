// コアごとの使用率の推移を、タスクマネージャー風の小さなグラフで表示する（SVG）。
// 横軸は「選択した期間の始まり ～ 現在」。データが途切れた時間は線をつなげない。

const VW = 300; // viewBox の幅（縦横比は CSS で決める。線の太さは拡大縮小しない）
const VH = 100;
const GAP_MS = 3 * 60_000; // これより間が空いたら線を切る（エージェントは 1 分ごとに送信）

export interface Point {
  t: number; // ミリ秒
  v: number; // 使用率 %
}

interface Props {
  index: number;
  points: Point[];
  from: number; // 期間の始まり（ミリ秒）
  to: number; // 期間の終わり（ミリ秒）
  periodLabel: string;
}

function paths(points: Point[], from: number, to: number) {
  const x = (t: number) => ((t - from) / (to - from)) * VW;
  const y = (v: number) => VH - (Math.max(0, Math.min(100, v)) / 100) * VH;
  const runs: Point[][] = [];
  for (const p of points) {
    const run = runs[runs.length - 1];
    if (run && p.t - run[run.length - 1].t <= GAP_MS) run.push(p);
    else runs.push([p]);
  }
  let line = "";
  let area = "";
  for (const run of runs) {
    const seg = run.map((p, i) => `${i ? "L" : "M"}${x(p.t).toFixed(1)} ${y(p.v).toFixed(1)}`).join(" ");
    line += seg + " ";
    area += `${seg} L${x(run[run.length - 1].t).toFixed(1)} ${VH} L${x(run[0].t).toFixed(1)} ${VH} Z `;
  }
  return { line, area };
}

export function CoreChart({ index, points, from, to, periodLabel }: Props) {
  const { line, area } = paths(points, from, to);
  const last = points[points.length - 1];
  return (
    <figure className="core-chart">
      <figcaption>
        <span>CORE-{index}{last && <b> {Math.round(last.v)}%</b>}</span>
        <span>100%</span>
      </figcaption>
      <svg viewBox={`0 0 ${VW} ${VH}`} preserveAspectRatio="none" role="img"
           aria-label={`CORE-${index} の使用率の推移（${periodLabel}）`}>
        <g className="grid">
          {Array.from({ length: 9 }, (_, i) => (
            <line key={`h${i}`} x1="0" x2={VW} y1={(VH / 10) * (i + 1)} y2={(VH / 10) * (i + 1)} />
          ))}
          {Array.from({ length: 19 }, (_, i) => (
            <line key={`v${i}`} y1="0" y2={VH} x1={(VW / 20) * (i + 1)} x2={(VW / 20) * (i + 1)} />
          ))}
        </g>
        <path d={area} className="area" />
        <path d={line} className="line" />
      </svg>
      <figcaption>
        <span>{periodLabel}</span>
        <span>0%</span>
      </figcaption>
    </figure>
  );
}
