package com.example.resourcemonitor.ops;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 「人のアクセス」の最終時刻を記録します。
 * エージェントの送信（POST /api/metrics）は 30 秒ごとに来るので数えません。数えると自動停止しなくなるためです。
 */
@Component
public class ActivityTracker implements HandlerInterceptor {

    private final AtomicReference<Instant> lastAccess = new AtomicReference<>(Instant.now());

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        boolean agentPost = "POST".equals(req.getMethod()) && "/api/metrics".equals(req.getRequestURI());
        if (!agentPost) {
            lastAccess.set(Instant.now());
        }
        return true;
    }

    public Instant lastAccess() {
        return lastAccess.get();
    }
}
