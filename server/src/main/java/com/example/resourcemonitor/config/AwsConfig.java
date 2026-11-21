package com.example.resourcemonitor.config;

import java.net.URI;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.route53.Route53Client;

/**
 * AWS SDK クライアント。認証情報とリージョンは SDK の既定の探索順で解決されます
 * （Fargate ではタスクロール、ローカルでは ~/.aws/credentials や AWS_REGION）。
 */
@Configuration
public class AwsConfig {

    @Bean
    DynamoDbClient dynamoDbClient(AppProperties props) {
        DynamoDbClientBuilder b = DynamoDbClient.builder().httpClientBuilder(UrlConnectionHttpClient.builder());
        String endpoint = props.dynamodb().endpoint();
        if (endpoint != null && !endpoint.isBlank()) {
            b.endpointOverride(URI.create(endpoint)); // DynamoDB Local 用
        }
        return b.build();
    }

    @Bean
    EcsClient ecsClient() {
        return EcsClient.builder().httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }

    @Bean
    Route53Client route53Client() {
        // Route 53 はグローバルサービス
        return Route53Client.builder().region(Region.AWS_GLOBAL)
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }
}
