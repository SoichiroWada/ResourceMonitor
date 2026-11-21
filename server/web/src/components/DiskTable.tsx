import type { Disk } from "../types";
import { gb, level } from "../format";

export function DiskTable({ disks }: { disks: Disk[] }) {
  return (
    <section className="card">
      <h2>ディスク構成と使用率</h2>
      <div className="scroll">
        <table>
          <thead>
            <tr><th>デバイス</th><th>マウント</th><th>FS</th><th>使用 / 容量</th><th>使用率</th><th></th></tr>
          </thead>
          <tbody>
            {disks.map((d) => (
              <tr key={d.device + d.mountpoint}>
                <td>{d.device}</td>
                <td>{d.mountpoint}</td>
                <td>{d.fsType}</td>
                <td>{gb(d.usedBytes)} / {gb(d.totalBytes)}</td>
                <td>{d.usedPercent.toFixed(1)}%</td>
                <td className="bar-cell">
                  <div className="bar"><span className={level(d.usedPercent)} style={{ width: `${Math.min(d.usedPercent, 100)}%` }} /></div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}
