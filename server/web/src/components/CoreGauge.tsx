// コアごとの使用率を表示するゲージ（SVG）。
// 針は使わず、中央に大きな数字、外周に沿った太い帯が使用率に応じて光る。

const W = 320;
const H = 240;
const CX = 160;
const CY = 150;
const R = 122; // 帯の中心の半径
const BAND = 20; // 帯の太さ
const START = 195; // 0% の角度（度。右が 0 度で反時計回り）
const END = -15; // 100% の角度
const RED_FROM = 80; // この値（%）以上は赤

const clamp = (v: number) => Math.max(0, Math.min(100, v));
const angleOf = (pct: number) => START - ((START - END) * clamp(pct)) / 100;

function polar(pct: number, r: number): [number, number] {
  const a = (angleOf(pct) * Math.PI) / 180;
  return [CX + r * Math.cos(a), CY - r * Math.sin(a)];
}

function arc(from: number, to: number, r: number): string {
  const [x0, y0] = polar(from, r);
  const [x1, y1] = polar(to, r);
  const large = angleOf(from) - angleOf(to) > 180 ? 1 : 0;
  return `M ${x0.toFixed(2)} ${y0.toFixed(2)} A ${r} ${r} 0 ${large} 1 ${x1.toFixed(2)} ${y1.toFixed(2)}`;
}

const MAJORS = Array.from({ length: 11 }, (_, i) => i * 10);

/** すべてのゲージで共有するグラデーションと光彩。ページに 1 回だけ置く。 */
export function GaugeDefs() {
  return (
    <svg width="0" height="0" style={{ position: "absolute" }} aria-hidden="true">
      <defs>
        <linearGradient id="g-bg" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#0d1319" />
          <stop offset="1" stopColor="#04070a" />
        </linearGradient>
        <linearGradient id="g-bezel" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#c9d2dc" />
          <stop offset=".5" stopColor="#4b5662" />
          <stop offset="1" stopColor="#1a2128" />
        </linearGradient>
        <linearGradient id="g-cyan" x1="0" y1="1" x2="1" y2="0">
          <stop offset="0" stopColor="#06b6d4" />
          <stop offset=".55" stopColor="#22d3ee" />
          <stop offset="1" stopColor="#67e8f9" />
        </linearGradient>
        <radialGradient id="g-face" cx=".5" cy=".62" r=".55">
          <stop offset="0" stopColor="#0c1a22" />
          <stop offset="1" stopColor="#05090c" />
        </radialGradient>
        <filter id="f-glow" x="-30%" y="-30%" width="160%" height="160%">
          <feGaussianBlur stdDeviation="4" result="b" />
          <feMerge>
            <feMergeNode in="b" />
            <feMergeNode in="SourceGraphic" />
          </feMerge>
        </filter>
        <filter id="f-soft" x="-30%" y="-30%" width="160%" height="160%">
          <feGaussianBlur stdDeviation="2" result="b" />
          <feMerge>
            <feMergeNode in="b" />
            <feMergeNode in="SourceGraphic" />
          </feMerge>
        </filter>
      </defs>
    </svg>
  );
}

interface Props {
  index: number; // 0 始まりのコア番号
  count: number; // 論理コア数
  value: number; // 現在の使用率 %
  avg: number | null; // 選択期間の平均 %
}

export function CoreGauge({ index, count, value, avg }: Props) {
  const v = clamp(value);
  const hot = v >= RED_FROM;
  const inner = R - BAND / 2;
  const outer = R + BAND / 2;

  return (
    <svg className="gauge" viewBox={`0 0 ${W} ${H}`} role="img"
         aria-label={`CORE-${index} 使用率 ${v.toFixed(1)}%`}>
      {/* 背景パネルと四隅のアクセント */}
      <rect x="2" y="2" width={W - 4} height={H - 4} rx="14" fill="url(#g-bg)" stroke="#25303a" strokeWidth="2" />
      <g stroke="#22d3ee" strokeWidth="3.5" strokeLinecap="round" fill="none" filter="url(#f-soft)">
        <path d="M 12 38 L 12 12 L 38 12" />
        <path d={`M ${W - 38} 12 L ${W - 12} 12 L ${W - 12} 38`} />
        <path d={`M 12 ${H - 38} L 12 ${H - 12} L 38 ${H - 12}`} />
        <path d={`M ${W - 38} ${H - 12} L ${W - 12} ${H - 12} L ${W - 12} ${H - 38}`} />
      </g>

      {/* 文字盤と金属の縁 */}
      <path d={`${arc(0, 100, outer + 4)} L ${CX} ${CY} Z`} fill="url(#g-face)" />
      <path d={arc(0, 100, outer + 5)} fill="none" stroke="url(#g-bezel)" strokeWidth="5" />
      <path d={arc(0, 100, inner - 30)} fill="none" stroke="#163039" strokeWidth="2" />

      {/* 帯: 全体は暗い灰色、現在値までを光らせる（80% を超えた分は赤） */}
      <path d={arc(0, 100, R)} fill="none" stroke="#1f2830" strokeWidth={BAND} />
      {v > 0 && (
        <path className="gauge-band-lit" d={arc(0, Math.min(v, RED_FROM), R)} fill="none"
              stroke="url(#g-cyan)" strokeWidth={BAND} filter="url(#f-glow)" />
      )}
      {v > RED_FROM && (
        <path d={arc(RED_FROM, v, R)} fill="none" stroke="#ef4444" strokeWidth={BAND} filter="url(#f-glow)" />
      )}

      {/* 目盛り（10% ごと）: 帯を横切る白い線と、内側の数字 */}
      {MAJORS.map((p) => {
        const [x0, y0] = polar(p, inner - 2);
        const [x1, y1] = polar(p, outer);
        return <line key={p} x1={x0} y1={y0} x2={x1} y2={y1} stroke="#f8fafc" strokeWidth="3" opacity=".9" />;
      })}
      {MAJORS.map((p) => {
        const [x, y] = polar(p, inner - 16);
        return (
          <text key={p} x={x} y={y} className="gauge-scale" textAnchor="middle" dominantBaseline="central"
                fill="#e5e7eb">
            {p}%
          </text>
        );
      })}

      {/* 中央の数字 */}
      <text x={CX} y={CY - 4} className="gauge-value" textAnchor="middle" fill={hot ? "#fecaca" : "#ffffff"}
            filter="url(#f-soft)">
        {Math.round(v)}%
      </text>
      <text x={CX} y={CY + 20} className="gauge-caption" textAnchor="middle" fill="#e2e8f0">CPU USAGE</text>
      <g stroke="#22d3ee" strokeWidth="2" fill="none" opacity=".8">
        <path d={`M ${CX - 64} ${CY + 12} L ${CX - 54} ${CY + 12} L ${CX - 49} ${CY + 18}`} />
        <path d={`M ${CX + 64} ${CY + 12} L ${CX + 54} ${CY + 12} L ${CX + 49} ${CY + 18}`} />
      </g>

      {/* 下の帯: 左にチップのアイコンと「何個目 / 全体」、中央にコア番号、右に期間平均 */}
      <rect x="36" y={H - 52} width={W - 72} height="40" rx="8" fill="#091017" stroke="#1d2a33" strokeWidth="1.5" />
      <g stroke="#e2e8f0" strokeWidth="2" fill="none">
        <rect x="50" y={H - 41} width="18" height="18" rx="3" />
        <path d={`M 54 ${H - 45} v 4 M 59 ${H - 45} v 4 M 64 ${H - 45} v 4 M 54 ${H - 23} v 4 M 59 ${H - 23} v 4 M 64 ${H - 23} v 4`} />
      </g>
      <text x="76" y={H - 37} className="gauge-band-small" fill="#94a3b8">CORES</text>
      <text x="76" y={H - 20} className="gauge-band" fill="#a5f3fc">{index + 1} / {count}</text>
      <text x={CX + 8} y={H - 25} className="gauge-core" textAnchor="middle" fill="#ffffff">CORE-{index}</text>
      <text x={W - 48} y={H - 37} className="gauge-band-small" textAnchor="end" fill="#94a3b8">AVG</text>
      <text x={W - 48} y={H - 20} className="gauge-band" textAnchor="end"
            fill={avg != null && avg >= RED_FROM ? "#f87171" : "#67e8f9"}>
        {avg == null ? "–" : `${Math.round(avg)}%`}
      </text>
    </svg>
  );
}
