package com.example.resourcemonitor.api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 長い期間の表示用に、時系列を 1 分ごとの平均にまとめます。
 *
 * <ul>
 *   <li>CPU（合計とコアごと）とメモリ使用率は、その 1 分間の平均</li>
 *   <li>ディスク・メモリ容量・ホスト名などは、その 1 分間の最後の値</li>
 *   <li>時刻は、その 1 分の始まり</li>
 * </ul>
 */
public final class Downsampler {

    private static final long MINUTE_MS = 60_000;

    private Downsampler() {}

    /** rows は時刻の古い順に並んでいること（DynamoDB の Query の結果はこの順）。 */
    public static List<MetricSnapshot> perMinute(List<MetricSnapshot> rows) {
        Map<Long, List<MetricSnapshot>> buckets = new LinkedHashMap<>();
        for (MetricSnapshot r : rows) {
            long key = Math.floorDiv(r.timestamp().toEpochMilli(), MINUTE_MS);
            buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }
        List<MetricSnapshot> out = new ArrayList<>(buckets.size());
        buckets.forEach((key, group) -> out.add(average(Instant.ofEpochMilli(key * MINUTE_MS), group)));
        return out;
    }

    private static MetricSnapshot average(Instant at, List<MetricSnapshot> group) {
        MetricSnapshot last = group.get(group.size() - 1);
        if (group.size() == 1) {
            return withTime(last, at);
        }
        int cores = last.cpu() == null || last.cpu().perCore() == null ? 0 : last.cpu().perCore().size();
        double[] coreSum = new double[cores];
        int[] coreCount = new int[cores];
        double totalSum = 0;
        int totalCount = 0;
        double memSum = 0;
        double memBytesSum = 0;
        int memCount = 0;
        for (MetricSnapshot s : group) {
            if (s.cpu() != null) {
                totalSum += s.cpu().totalPercent();
                totalCount++;
                List<Double> pc = s.cpu().perCore();
                for (int i = 0; pc != null && i < Math.min(cores, pc.size()); i++) {
                    if (pc.get(i) != null) {
                        coreSum[i] += pc.get(i);
                        coreCount[i]++;
                    }
                }
            }
            if (s.memory() != null) {
                memSum += s.memory().usedPercent();
                memBytesSum += s.memory().usedBytes();
                memCount++;
            }
        }
        MetricSnapshot.Cpu cpu = last.cpu();
        if (cpu != null && totalCount > 0) {
            List<Double> perCore = new ArrayList<>(cores);
            for (int i = 0; i < cores; i++) {
                perCore.add(coreCount[i] == 0 ? 0 : round1(coreSum[i] / coreCount[i]));
            }
            cpu = new MetricSnapshot.Cpu(cpu.model(), round1(totalSum / totalCount), perCore);
        }
        MetricSnapshot.Memory mem = last.memory();
        if (mem != null && memCount > 0) {
            mem = new MetricSnapshot.Memory(mem.totalBytes(), Math.round(memBytesSum / memCount),
                    round1(memSum / memCount));
        }
        return new MetricSnapshot(last.hostId(), last.hostname(), last.os(), last.platform(), at, cpu, mem,
                last.disks());
    }

    private static MetricSnapshot withTime(MetricSnapshot s, Instant at) {
        return new MetricSnapshot(s.hostId(), s.hostname(), s.os(), s.platform(), at, s.cpu(), s.memory(),
                s.disks());
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
