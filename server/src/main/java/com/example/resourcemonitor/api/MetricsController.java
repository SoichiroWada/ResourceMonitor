package com.example.resourcemonitor.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.resourcemonitor.config.AppProperties;
import com.example.resourcemonitor.store.MetricsRepository;
import com.example.resourcemonitor.store.MetricsRepository.HostSummary;

@RestController
@RequestMapping("/api")
public class MetricsController {

    private final MetricsRepository repo;
    private final Set<String> apiKeys;

    public MetricsController(MetricsRepository repo, AppProperties props) {
        this.repo = repo;
        this.apiKeys = props.apiKeySet();
    }

    /** エージェントからの受信。 */
    @PostMapping("/metrics")
    public ResponseEntity<Void> receive(@RequestHeader(name = "X-Api-Key", required = false) String apiKey,
                                        @RequestBody MetricSnapshot snapshot) {
        if (!isValidKey(apiKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (snapshot.hostId() == null || snapshot.hostId().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        if (snapshot.timestamp() == null) {
            snapshot = new MetricSnapshot(snapshot.hostId(), snapshot.hostname(), snapshot.os(), snapshot.platform(),
                    Instant.now(), snapshot.cpu(), snapshot.memory(), snapshot.disks());
        }
        repo.save(snapshot);
        return ResponseEntity.accepted().build();
    }

    /** ホスト一覧と最新値（ダッシュボード用）。 */
    @GetMapping("/hosts")
    public List<HostSummary> hosts() {
        return repo.listHosts();
    }

    /**
     * 指定ホストの時系列（既定: 直近 1 時間、最大 24 時間）。
     * 期間は minutes（分）で指定する。minutes がなければ hours（時間）を使う（旧画面との互換用）。
     */
    @GetMapping("/hosts/{hostId}/metrics")
    public List<MetricSnapshot> history(@PathVariable String hostId,
                                        @RequestParam(required = false) Integer minutes,
                                        @RequestParam(defaultValue = "1") int hours) {
        int m = minutes != null ? minutes : hours * 60;
        m = Math.max(1, Math.min(m, 24 * 60));
        return repo.findSince(hostId, Instant.now().minus(Duration.ofMinutes(m)));
    }

    private boolean isValidKey(String key) {
        if (key == null) {
            return false;
        }
        byte[] given = key.getBytes(StandardCharsets.UTF_8);
        // 定数時間比較（タイミング攻撃対策）
        return apiKeys.stream().anyMatch(k -> MessageDigest.isEqual(k.getBytes(StandardCharsets.UTF_8), given));
    }
}
