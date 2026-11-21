package com.example.resourcemonitor.sw;

import java.util.Map;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;

import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.Service;

/**
 * API Gateway (HTTP API) から呼ばれる起動/停止スイッチ。
 * Cognito の JWT 検証は API Gateway 側（JWT オーソライザー）で済んでいる前提です。
 *
 * <pre>
 * GET  /status → {"desired":1,"running":1,"pending":0,"state":"RUNNING","dashboardUrl":"..."}
 * POST /start  → desiredCount = 1
 * POST /stop   → desiredCount = 0
 * </pre>
 */
public class SwitchHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    // コールドスタート時に 1 回だけ作る（SnapStart の対象にもなる）
    private static final EcsClient ECS = EcsClient.builder()
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .build();

    private final String cluster = env("ECS_CLUSTER");
    private final String service = env("ECS_SERVICE");
    private final String dashboardUrl = env("DASHBOARD_URL");

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
        String route = event.getRouteKey(); // 例: "POST /start"
        String user = claim(event, "email");
        try {
            switch (route) {
                case "POST /start" -> {
                    context.getLogger().log("start requested by " + user);
                    setDesired(1);
                }
                case "POST /stop" -> {
                    context.getLogger().log("stop requested by " + user);
                    setDesired(0);
                }
                case "GET /status" -> { /* 下で状態を返す */ }
                default -> {
                    return json(404, "{\"error\":\"not found\"}");
                }
            }
            return json(200, statusJson());
        } catch (RuntimeException e) {
            context.getLogger().log("error: " + e);
            return json(500, "{\"error\":\"" + esc(e.getMessage()) + "\"}");
        }
    }

    private void setDesired(int count) {
        ECS.updateService(r -> r.cluster(cluster).service(service).desiredCount(count));
    }

    private String statusJson() {
        Service s = ECS.describeServices(r -> r.cluster(cluster).services(service)).services().getFirst();
        int desired = s.desiredCount(), running = s.runningCount(), pending = s.pendingCount();
        String state;
        if (desired == 0 && running == 0 && pending == 0) {
            state = "STOPPED";
        } else if (desired == 0) {
            state = "STOPPING";
        } else if (running >= desired && pending == 0) {
            state = "RUNNING";
        } else {
            state = "STARTING";
        }
        return "{\"desired\":%d,\"running\":%d,\"pending\":%d,\"state\":\"%s\",\"dashboardUrl\":\"%s\"}"
                .formatted(desired, running, pending, state, esc(dashboardUrl));
    }

    private static String claim(APIGatewayV2HTTPEvent e, String name) {
        try {
            Map<String, String> claims = e.getRequestContext().getAuthorizer().getJwt().getClaims();
            return claims.getOrDefault(name, claims.getOrDefault("sub", "?"));
        } catch (NullPointerException ex) {
            return "?";
        }
    }

    private static APIGatewayV2HTTPResponse json(int status, String body) {
        return APIGatewayV2HTTPResponse.builder()
                .withStatusCode(status)
                .withHeaders(Map.of("Content-Type", "application/json"))
                .withBody(body)
                .build();
    }

    private static String env(String k) {
        String v = System.getenv(k);
        return v == null ? "" : v;
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
