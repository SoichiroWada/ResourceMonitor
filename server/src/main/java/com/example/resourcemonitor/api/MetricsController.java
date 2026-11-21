package com.example.resourcemonitor.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api")
public class MetricsController {

    /** 1 回の受信で受け付ける最大件数（エージェントは通常 3 件、一時保存分の再送で最大 60 件）。 */
    private static final int MAX_BATCH = 500;
    /** この期間（分）までは間引かずに返す。 */
    private static final int RAW_MAX_MINUTES = 60;

    private final MetricsRepository repo;
    private final ObjectMapper json;
    private final Set<String> apiKeys;

    public MetricsController(MetricsRepository repo, ObjectMapper json, AppProperties props) {
        this.repo = repo;
        this.json = json;
        this.apiKeys = props.apiKeySet();
    }

    /**
     * エージェントからの受信。1 件（JSON オブジェクト）と、まとめて送る複数件（JSON 配列）の両方を受け付ける。
     * 1 件の形式は旧版のエージェント用。パスを分けないのは、自動停止の判定とセキュリティ設定が
     * このパスだけをエージェント用として扱っているため。
     */
    @PostMapping("/metrics")
    public ResponseEntity<Void> receive(@RequestHeader(name = "X-Api-Key", required = false) String apiKey,
                                        @RequestBody JsonNode body) {
        if (!isValidKey(apiKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        List<JsonNode> nodes = new ArrayList<>();
        if (body.isArray()) {
            body.forEach(nodes::add);
        } else {
            nodes.add(body);
        }
        if (nodes.isEmpty() || nodes.size() > MAX_BATCH) {
            return ResponseEntity.badRequest().build();
        }
        List<MetricSnapshot> snapshots = new ArrayList<>(nodes.size());
        for (JsonNode n : nodes) {
            MetricSnapshot s;
            try {
                s = json.treeToValue(n, MetricSnapshot.class);
            } catch (JacksonException e) {
                return ResponseEntity.badRequest().build();
            }
            if (s == null || s.hostId() == null || s.hostId().isBlank()) {
                return ResponseEntity.badRequest().build();
            }
            if (s.timestamp() == null) {
                s = new MetricSnapshot(s.hostId(), s.hostname(), s.os(), s.platform(),
                        Instant.now(), s.cpu(), s.memory(), s.disks());
            }
            snapshots.add(s);
        }
        repo.saveAll(snapshots);
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
     * 1 時間を超える期間は、データ量を抑えるため 1 分ごとの平均にまとめて返す。
     */
    @GetMapping("/hosts/{hostId}/metrics")
    public List<MetricSnapshot> history(@PathVariable String hostId,
                                        @RequestParam(required = false) Integer minutes,
                                        @RequestParam(defaultValue = "1") int hours) {
        int m = minutes != null ? minutes : hours * 60;
        m = Math.max(1, Math.min(m, 24 * 60));
        List<MetricSnapshot> rows = repo.findSince(hostId, Instant.now().minus(Duration.ofMinutes(m)));
        return m > RAW_MAX_MINUTES ? Downsampler.perMinute(rows) : rows;
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
