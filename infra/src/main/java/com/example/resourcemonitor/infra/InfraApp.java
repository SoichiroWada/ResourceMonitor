package com.example.resourcemonitor.infra;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import software.amazon.awscdk.App;
import software.amazon.awscdk.AppProps;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;

public final class InfraApp {

    /**
     * 個人ごとの設定値（ドメイン名・メールアドレスなど）を置くファイル。Git には入れない。
     * 雛形は cdk.local.example.json。
     */
    private static final Path LOCAL_CONFIG = Path.of("cdk.local.json");

    /** cdk.local.json に必ず書く値。 */
    private static final String[] REQUIRED = {"zoneName", "cognitoDomainPrefix"};

    public static void main(String[] args) throws IOException {
        Map<String, Object> local = loadLocalConfig();
        App app = new App(AppProps.builder().context(local).build());
        new ResourceMonitorStack(app, "ResourceMonitorStack", StackProps.builder()
                .env(Environment.builder()
                        .account(System.getenv("CDK_DEFAULT_ACCOUNT"))
                        .region(System.getenv("CDK_DEFAULT_REGION"))
                        .build())
                .description("Resource Monitor: Fargate dashboard + on/off switch")
                .build());
        app.synth();
    }

    private static Map<String, Object> loadLocalConfig() throws IOException {
        if (!Files.exists(LOCAL_CONFIG)) {
            throw new IllegalStateException(
                    "infra/cdk.local.json がありません。cdk.local.example.json をコピーして値を設定してください。");
        }
        Map<String, Object> m = new ObjectMapper().readValue(LOCAL_CONFIG.toFile(), new TypeReference<>() {});
        for (String key : REQUIRED) {
            Object v = m.get(key);
            if (v == null || v.toString().isBlank()) {
                throw new IllegalStateException("infra/cdk.local.json に " + key + " を設定してください。");
            }
        }
        return m;
    }
}
