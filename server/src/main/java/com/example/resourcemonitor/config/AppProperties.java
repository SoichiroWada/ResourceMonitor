package com.example.resourcemonitor.config;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** application.yml の app.* をまとめた設定。 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(String agentApiKeys, Dynamo dynamodb, AutoStop autoStop, Dns dns) {

    public record Dynamo(String hostsTable, String metricsTable, int retentionDays,
                         String endpoint, boolean createTables) {}

    public record AutoStop(boolean enabled, int idleMinutes, String cluster, String service) {}

    public record Dns(boolean enabled, String hostedZoneId, String recordName, long ttl) {}

    public Set<String> apiKeySet() {
        return Arrays.stream(agentApiKeys.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }
}
