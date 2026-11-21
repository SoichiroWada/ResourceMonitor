package com.example.resourcemonitor.api;

import java.time.Instant;
import java.util.List;

/** エージェント（Go の collector.Snapshot）から届く JSON。 */
public record MetricSnapshot(
        String hostId,
        String hostname,
        String os,
        String platform,
        Instant timestamp,
        Cpu cpu,
        Memory memory,
        List<Disk> disks) {

    public record Cpu(String model, double totalPercent, List<Double> perCore) {}

    public record Memory(long totalBytes, long usedBytes, double usedPercent) {}

    public record Disk(String device, String mountpoint, String fsType,
                       long totalBytes, long usedBytes, double usedPercent) {}
}
