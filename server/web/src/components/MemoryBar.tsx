import type { Memory } from "../types";
import { gb, level } from "../format";

// メモリ使用率を、縦幅を取らない 1 行の横長バーで表示する
export function MemoryBar({ memory }: { memory: Memory }) {
  const p = memory.usedPercent;
  return (
    <section className="card memory">
      <h2>メモリ</h2>
      <div className="memory-bar" role="meter" aria-valuemin={0} aria-valuemax={100} aria-valuenow={p}
           aria-label="メモリ使用率">
        <span className={level(p)} style={{ width: `${Math.min(p, 100)}%` }} />
      </div>
      <div className="memory-text">
        <b>{p.toFixed(1)}%</b>
        <span className="meta">{gb(memory.usedBytes)} / {gb(memory.totalBytes)}</span>
      </div>
    </section>
  );
}
