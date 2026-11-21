package com.example.resourcemonitor.store;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Repository;

import com.example.resourcemonitor.api.MetricSnapshot;
import com.example.resourcemonitor.config.AppProperties;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.PutRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;
import tools.jackson.databind.ObjectMapper;

/**
 * DynamoDB への保存と参照。
 *
 * <pre>
 * Hosts テーブル   PK: hostId (S)                 … ホストごとの最新値（一覧表示用）
 * Metrics テーブル PK: hostId (S), SK: ts (N, ms) … 時系列。expiresAt (TTL) で自動削除
 * </pre>
 *
 * スナップショット本体は JSON 文字列（data 属性）でそのまま保存します。
 */
@Repository
public class MetricsRepository {

    public record HostSummary(String hostId, String hostname, String os, String platform,
                              Instant lastSeen, MetricSnapshot latest) {}

    /** BatchWriteItem で 1 回に書ける最大件数。 */
    private static final int BATCH_WRITE_MAX = 25;

    private final DynamoDbClient ddb;
    private final ObjectMapper json;
    private final String hostsTable;
    private final String metricsTable;
    private final Duration retention;

    public MetricsRepository(DynamoDbClient ddb, ObjectMapper json, AppProperties props) {
        this.ddb = ddb;
        this.json = json;
        this.hostsTable = props.dynamodb().hostsTable();
        this.metricsTable = props.dynamodb().metricsTable();
        this.retention = Duration.ofDays(props.dynamodb().retentionDays());
    }

    /**
     * 時系列をまとめて保存し、ホストごとの最新値を更新します。
     * 一時保存分の再送では古いデータが後から届くため、最新値は今より新しいときだけ書き換えます。
     */
    public void saveAll(List<MetricSnapshot> snapshots) {
        List<WriteRequest> writes = new ArrayList<>(snapshots.size());
        Map<String, MetricSnapshot> newest = new LinkedHashMap<>();
        for (MetricSnapshot s : snapshots) {
            writes.add(WriteRequest.builder().putRequest(PutRequest.builder().item(Map.of(
                    "hostId", str(s.hostId()),
                    "ts", num(s.timestamp().toEpochMilli()),
                    "data", str(json.writeValueAsString(s)),
                    "expiresAt", num(s.timestamp().plus(retention).getEpochSecond()))).build()).build());
            newest.merge(s.hostId(), s, (a, b) -> b.timestamp().isAfter(a.timestamp()) ? b : a);
        }
        for (int i = 0; i < writes.size(); i += BATCH_WRITE_MAX) {
            batchWrite(writes.subList(i, Math.min(i + BATCH_WRITE_MAX, writes.size())));
        }
        newest.values().forEach(this::updateLatest);
    }

    /** BatchWriteItem は書けなかった分（UnprocessedItems）を返すことがあるので、少し待って書き直す。 */
    private void batchWrite(List<WriteRequest> chunk) {
        Map<String, List<WriteRequest>> req = Map.of(metricsTable, chunk);
        for (int attempt = 0; !req.isEmpty(); attempt++) {
            if (attempt >= 5) {
                throw new IllegalStateException("DynamoDB への書き込みが完了しませんでした");
            }
            if (attempt > 0) {
                try {
                    Thread.sleep(100L << attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            Map<String, List<WriteRequest>> current = req;
            req = ddb.batchWriteItem(r -> r.requestItems(current)).unprocessedItems();
        }
    }

    private void updateLatest(MetricSnapshot s) {
        long ts = s.timestamp().toEpochMilli();
        try {
            ddb.putItem(r -> r.tableName(hostsTable)
                    .item(Map.of(
                            "hostId", str(s.hostId()),
                            "hostname", str(nz(s.hostname())),
                            "os", str(nz(s.os())),
                            "platform", str(nz(s.platform())),
                            "lastSeen", num(ts),
                            "data", str(json.writeValueAsString(s))))
                    .conditionExpression("attribute_not_exists(lastSeen) OR lastSeen < :ts")
                    .expressionAttributeValues(Map.of(":ts", num(ts))));
        } catch (ConditionalCheckFailedException e) {
            // すでにもっと新しい値が入っている（古いデータの再送）。最新値はそのまま
        }
    }

    public List<HostSummary> listHosts() {
        List<HostSummary> out = new ArrayList<>();
        // ホスト数は少ない前提なので Scan で十分
        ddb.scanPaginator(ScanRequest.builder().tableName(hostsTable).build()).items().forEach(it -> out.add(
                new HostSummary(
                        it.get("hostId").s(),
                        s(it, "hostname"), s(it, "os"), s(it, "platform"),
                        Instant.ofEpochMilli(Long.parseLong(it.get("lastSeen").n())),
                        parse(it.get("data").s()))));
        out.sort(Comparator.comparing(HostSummary::hostId));
        return out;
    }

    public List<MetricSnapshot> findSince(String hostId, Instant from) {
        QueryRequest q = QueryRequest.builder()
                .tableName(metricsTable)
                .keyConditionExpression("hostId = :h AND ts >= :from")
                .expressionAttributeValues(Map.of(":h", str(hostId), ":from", num(from.toEpochMilli())))
                .projectionExpression("#d")
                .expressionAttributeNames(Map.of("#d", "data"))
                .build();
        List<MetricSnapshot> out = new ArrayList<>();
        ddb.queryPaginator(q).items().forEach(it -> out.add(parse(it.get("data").s())));
        return out;
    }

    private MetricSnapshot parse(String data) {
        return json.readValue(data, MetricSnapshot.class);
    }

    private static AttributeValue str(String v) { return AttributeValue.fromS(v); }
    private static AttributeValue num(long v) { return AttributeValue.fromN(Long.toString(v)); }
    private static String nz(String v) { return v == null ? "" : v; }
    private static String s(Map<String, AttributeValue> it, String k) {
        AttributeValue v = it.get(k);
        return v == null ? "" : v.s();
    }
}
