// コアごとの使用率を、車のタコメーター風の半円ゲージで表示する（SVG）

const W = 320;
const H = 300;
const CX = 160;
const CY = 150;
const R = 120; // 目盛りの円弧の半径
const START = 200; // 0% の角度（度。右が 0 度で反時計回り）
const END = -20; // 100% の角度
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

const TICKS = Array.from({ length: 51 }, (_, i) => i * 2);

/** すべてのゲージで共有するグラデーションと光彩。ページに 1 回だけ置く。 */
export function GaugeDefs() {
  return (
    <svg width="0" height="0" style={{ position: "absolute" }} aria-hidden="true">
      <defs>
        <linearGradient id="g-bg" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#10161d" />
          <stop offset="1" stopColor="#05080b" />
        </linearGradient>
        <linearGradient id="g-bezel" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#d7dee6" />
          <stop offset=".45" stopColor="#5b6672" />
          <stop offset="1" stopColor="#1c232b" />
        </linearGradient>
        <linearGradient id="g-cyan" x1="0" y1="1" x2="1" y2="0">
          <stop offset="0" stopColor="#0ea5e9" />
          <stop offset=".6" stopColor="#22d3ee" />
          <stop offset="1" stopColor="#a5f3fc" />
        </linearGradient>
        <radialGradient id="g-face" cx=".5" cy=".55" r=".6">
          <stop offset="0" stopColor="#0f1820" />
          <stop offset="1" stopColor="#070a0e" />
        </radialGradient>
        <filter id="f-glow" x="-50%" y="-50%" width="200%" height="200%">
          <feGaussianBlur stdDeviation="3.2" result="b" />
          <feMerge>
            <feMergeNode in="b" />
            <feMergeNode in="SourceGraphic" />
          </feMerge>
        </filter>
        <filter id="f-soft" x="-50%" y="-50%" width="200%" height="200%">
          <feGaussianBlur stdDeviation="1.4" result="b" />
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
  const needleColor = hot ? "#f87171" : "#67e8f9";
  const rotate = 90 - angleOf(v); // 真上向きの針を、値の角度まで時計回りに回す

  return (
    <svg className="gauge" viewBox={`0 0 ${W} ${H}`} role="img"
         aria-label={`CORE-${index} 使用率 ${v.toFixed(1)}%`}>
      {/* 背景パネルと四隅のアクセント */}
      <rect x="2" y="2" width={W - 4} height={H - 4} rx="18" fill="url(#g-bg)" stroke="#26313b" strokeWidth="2" />
      <g stroke="#22d3ee" strokeWidth="3" strokeLinecap="round" opacity=".85" filter="url(#f-soft)">
        <path d="M 14 34 L 14 14 L 34 14" fill="none" />
        <path d={`M ${W - 34} 14 L ${W - 14} 14 L ${W - 14} 34`} fill="none" />
        <path d={`M 14 ${H - 34} L 14 ${H - 14} L 34 ${H - 14}`} fill="none" />
        <path d={`M ${W - 34} ${H - 14} L ${W - 14} ${H - 14} L ${W - 14} ${H - 34}`} fill="none" />
      </g>

      {/* 文字盤と金属の縁 */}
      <path d={`${arc(0, 100, 138)} L ${CX} ${CY} Z`} fill="url(#g-face)" />
      <path d={arc(0, 100, 138)} fill="none" stroke="url(#g-bezel)" strokeWidth="6" strokeLinecap="round" />
      <path d={arc(0, 100, 76)} fill="none" stroke="#1d2a33" strokeWidth="2" />

      {/* 色の帯: 全体は薄く、現在値までを明るく光らせる */}
      <path d={arc(0, RED_FROM, R + 6)} fill="none" stroke="url(#g-cyan)" strokeWidth="7" opacity=".28" />
      <path d={arc(RED_FROM, 100, R + 6)} fill="none" stroke="#ef4444" strokeWidth="7" opacity=".35" />
      {v > 0 && (
        <path d={arc(0, Math.min(v, RED_FROM), R + 6)} fill="none" stroke="url(#g-cyan)" strokeWidth="7"
              filter="url(#f-glow)" />
      )}
      {v > RED_FROM && (
        <path d={arc(RED_FROM, v, R + 6)} fill="none" stroke="#ef4444" strokeWidth="7" filter="url(#f-glow)" />
      )}

      {/* 目盛り（2% ごと、10% ごとに太線と数字） */}
      {TICKS.map((p) => {
        const major = p % 10 === 0;
        const [x0, y0] = polar(p, major ? R - 16 : R - 8);
        const [x1, y1] = polar(p, R);
        return (
          <line key={p} x1={x0} y1={y0} x2={x1} y2={y1}
                stroke={p >= RED_FROM ? "#fca5a5" : major ? "#f1f5f9" : "#7b8794"}
                strokeWidth={major ? 2.6 : 1.1} />
        );
      })}
      {TICKS.filter((p) => p % 10 === 0).map((p) => {
        const [x, y] = polar(p, R - 31);
        return (
          <text key={p} x={x} y={y} className="gauge-scale" textAnchor="middle" dominantBaseline="central"
                fill={p >= RED_FROM ? "#f87171" : "#e5e7eb"}>
            {p}%
          </text>
        );
      })}

      {/* 針と中心 */}
      <g className="gauge-needle" style={{ transform: `rotate(${rotate}deg)`, transformOrigin: `${CX}px ${CY}px` }}
         filter="url(#f-glow)">
        <polygon points={`${CX - 4},${CY} ${CX},${CY - (R - 10)} ${CX + 4},${CY}`} fill={needleColor} />
      </g>
      <circle cx={CX} cy={CY} r="15" fill="#0b1015" stroke="url(#g-bezel)" strokeWidth="4" />

      {/* 数値 */}
      <text x={CX} y={CY + 64} className="gauge-value" textAnchor="middle" fill={hot ? "#fecaca" : "#ffffff"}
            filter="url(#f-soft)">
        {Math.round(v)}%
      </text>
      <text x={CX} y={CY + 84} className="gauge-caption" textAnchor="middle" fill="#cbd5e1">CPU USAGE</text>

      {/* 下の帯: 左に「何個目 / 全体」、中央にコア番号、右に期間平均 */}
      <rect x="42" y={H - 58} width={W - 84} height="42" rx="7" fill="#0a1117" stroke="#1e2a33" />
      <text x="54" y={H - 42} className="gauge-band-small" fill="#94a3b8">CORES</text>
      <text x="54" y={H - 23} className="gauge-band" fill="#a5f3fc">{index + 1} / {count}</text>
      <text x={CX} y={H - 30} className="gauge-core" textAnchor="middle" fill="#ffffff">CORE-{index}</text>
      <text x={W - 54} y={H - 42} className="gauge-band-small" textAnchor="end" fill="#94a3b8">AVG</text>
      <text x={W - 54} y={H - 23} className="gauge-band" textAnchor="end"
            fill={avg != null && avg >= RED_FROM ? "#f87171" : "#67e8f9"}>
        {avg == null ? "–" : `${Math.round(avg)}%`}
      </text>
    </svg>
  );
}
