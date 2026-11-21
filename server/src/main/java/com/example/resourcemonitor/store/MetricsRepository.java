package com.example.resourcemonitor.store;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Repository;

import com.example.resourcemonitor.api.MetricSnapshot;
import com.example.resourcemonitor.config.AppProperties;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
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

    public void save(MetricSnapshot s) {
        String data = json.writeValueAsString(s);
        long ts = s.timestamp().toEpochMilli();
        long expiresAt = s.timestamp().plus(retention).getEpochSecond();

        ddb.putItem(r -> r.tableName(metricsTable).item(Map.of(
                "hostId", str(s.hostId()),
                "ts", num(ts),
                "data", str(data),
                "expiresAt", num(expiresAt))));

        ddb.putItem(r -> r.tableName(hostsTable).item(Map.of(
                "hostId", str(s.hostId()),
                "hostname", str(nz(s.hostname())),
                "os", str(nz(s.os())),
                "platform", str(nz(s.platform())),
                "lastSeen", num(ts),
                "data", str(data))));
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
