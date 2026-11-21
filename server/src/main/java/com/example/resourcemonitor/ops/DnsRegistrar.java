package com.example.resourcemonitor.ops;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.example.resourcemonitor.config.AppProperties;

import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.route53.model.ChangeAction;
import software.amazon.awssdk.services.route53.model.RRType;

/**
 * 起動時に自分のパブリック IP を調べ、Route 53 の A レコード（例: api.monitor.example.jp）を書き換えます。
 * ALB なしの構成で、タスクの IP が起動のたびに変わる問題への対策です。
 */
@Component
@ConditionalOnProperty(name = "app.dns.enabled", havingValue = "true")
public class DnsRegistrar {

    private static final Logger log = LoggerFactory.getLogger(DnsRegistrar.class);
    private static final URI CHECK_IP = URI.create("https://checkip.amazonaws.com/");

    private final Route53Client route53;
    private final AppProperties.Dns cfg;

    public DnsRegistrar(Route53Client route53, AppProperties props) {
        this.route53 = route53;
        this.cfg = props.dns();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void register() {
        try {
            String ip = publicIp();
            route53.changeResourceRecordSets(r -> r
                    .hostedZoneId(cfg.hostedZoneId())
                    .changeBatch(b -> b.comment("resource-monitor task started").changes(c -> c
                            .action(ChangeAction.UPSERT)
                            .resourceRecordSet(rr -> rr
                                    .name(cfg.recordName())
                                    .type(RRType.A)
                                    .ttl(cfg.ttl())
                                    .resourceRecords(v -> v.value(ip))))));
            log.info("DNS を更新しました: {} -> {}", cfg.recordName(), ip);
        } catch (Exception e) {
            // DNS 更新に失敗してもアプリ自体は動かす
            log.error("DNS の更新に失敗しました", e);
        }
    }

    private String publicIp() throws Exception {
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(CHECK_IP).timeout(Duration.ofSeconds(5)).build(),
                    HttpResponse.BodyHandlers.ofString());
            return res.body().trim();
        }
    }
}
