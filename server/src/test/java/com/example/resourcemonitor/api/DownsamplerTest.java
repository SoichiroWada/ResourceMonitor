package com.example.resourcemonitor.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

class DownsamplerTest {

    private static MetricSnapshot snap(String time, double total, List<Double> perCore, double mem) {
        return new MetricSnapshot("h", "h", "linux", "ubuntu", Instant.parse(time),
                new MetricSnapshot.Cpu("cpu", total, perCore),
                new MetricSnapshot.Memory(1000, (long) (mem * 10), mem), List.of());
    }

    @Test
    void averagesEachMinute() {
        List<MetricSnapshot> out = Downsampler.perMinute(List.of(
                snap("2026-11-22T00:00:05Z", 10, List.of(0.0, 20.0), 50),
                snap("2026-11-22T00:00:15Z", 20, List.of(10.0, 30.0), 60),
                snap("2026-11-22T00:00:55Z", 30, List.of(20.0, 40.0), 70),
                snap("2026-11-22T00:01:05Z", 90, List.of(80.0, 100.0), 80)));

        assertEquals(2, out.size());
        MetricSnapshot first = out.get(0);
        assertEquals(Instant.parse("2026-11-22T00:00:00Z"), first.timestamp());
        assertEquals(20.0, first.cpu().totalPercent());
        assertEquals(List.of(10.0, 30.0), first.cpu().perCore());
        assertEquals(60.0, first.memory().usedPercent());
        assertEquals(600, first.memory().usedBytes());

        MetricSnapshot second = out.get(1);
        assertEquals(Instant.parse("2026-11-22T00:01:00Z"), second.timestamp());
        assertEquals(90.0, second.cpu().totalPercent());
    }
}
