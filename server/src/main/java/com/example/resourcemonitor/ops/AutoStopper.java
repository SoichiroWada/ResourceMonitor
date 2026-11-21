package com.example.resourcemonitor.ops;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.resourcemonitor.config.AppProperties;

import software.amazon.awssdk.services.ecs.EcsClient;

/**
 * 最後の（人の）アクセスから idle-minutes 経過したら、自分の ECS サービスの desiredCount を 0 にします。
 * ECS がこのタスクを停止するので、Fargate の課金が止まります。
 */
@Component
@ConditionalOnProperty(name = "app.auto-stop.enabled", havingValue = "true")
public class AutoStopper {

    private static final Logger log = LoggerFactory.getLogger(AutoStopper.class);

    private final EcsClient ecs;
    private final ActivityTracker tracker;
    private final AppProperties.AutoStop cfg;
    private volatile boolean stopRequested;

    public AutoStopper(EcsClient ecs, ActivityTracker tracker, AppProperties props) {
        this.ecs = ecs;
        this.tracker = tracker;
        this.cfg = props.autoStop();
        log.info("自動停止を有効化: 無操作 {} 分で {}/{} を停止します", cfg.idleMinutes(), cfg.cluster(), cfg.service());
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void check() {
        if (stopRequested) {
            return;
        }
        Duration idle = Duration.between(tracker.lastAccess(), Instant.now());
        if (idle.toMinutes() < cfg.idleMinutes()) {
            return;
        }
        log.info("{} 分間アクセスが無いため停止します（desiredCount=0）", idle.toMinutes());
        try {
            ecs.updateService(r -> r.cluster(cfg.cluster()).service(cfg.service()).desiredCount(0));
            stopRequested = true;
        } catch (RuntimeException e) {
            log.error("サービスの停止に失敗しました。次回再試行します", e);
        }
    }
}
