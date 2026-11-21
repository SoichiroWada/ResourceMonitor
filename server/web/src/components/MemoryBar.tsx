import type { Cpu, Memory } from "../types";
import { gb, level } from "../format";

// メモリ使用率のカード。横に長くなりすぎないよう、幅を抑えて大きな数字とバーで表示する
export function MemoryBar({ memory, cpu }: { memory: Memory; cpu: Cpu }) {
  const p = memory.usedPercent;
  return (
    <section className="card memory">
      <h2>メモリ</h2>
      <div className="memory-value">{p.toFixed(1)}%</div>
      <div className="memory-bar" role="meter" aria-valuemin={0} aria-valuemax={100} aria-valuenow={p}
           aria-label="メモリ使用率">
        <span className={level(p)} style={{ width: `${Math.min(p, 100)}%` }} />
      </div>
      <p className="meta">{gb(memory.usedBytes)} / {gb(memory.totalBytes)} 使用</p>
      <p className="meta">CPU 合計: {cpu.totalPercent.toFixed(1)}%（{cpu.perCore.length} 論理コア）</p>
    </section>
  );
}
