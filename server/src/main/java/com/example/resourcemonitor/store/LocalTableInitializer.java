package com.example.resourcemonitor.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.example.resourcemonitor.config.AppProperties;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

/** ローカル開発（DynamoDB Local）用: テーブルが無ければ作成します。AWS 上では CDK が作成します。 */
@Component
@ConditionalOnProperty(name = "app.dynamodb.create-tables", havingValue = "true")
public class LocalTableInitializer {

    private static final Logger log = LoggerFactory.getLogger(LocalTableInitializer.class);
    private final DynamoDbClient ddb;
    private final AppProperties props;

    public LocalTableInitializer(DynamoDbClient ddb, AppProperties props) {
        this.ddb = ddb;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void createTables() {
        create(props.dynamodb().hostsTable(), false);
        create(props.dynamodb().metricsTable(), true);
    }

    private void create(String table, boolean withSortKey) {
        try {
            ddb.createTable(r -> {
                r.tableName(table).billingMode(BillingMode.PAY_PER_REQUEST);
                if (withSortKey) {
                    r.attributeDefinitions(
                            AttributeDefinition.builder().attributeName("hostId").attributeType(ScalarAttributeType.S).build(),
                            AttributeDefinition.builder().attributeName("ts").attributeType(ScalarAttributeType.N).build())
                     .keySchema(
                            KeySchemaElement.builder().attributeName("hostId").keyType(KeyType.HASH).build(),
                            KeySchemaElement.builder().attributeName("ts").keyType(KeyType.RANGE).build());
                } else {
                    r.attributeDefinitions(
                            AttributeDefinition.builder().attributeName("hostId").attributeType(ScalarAttributeType.S).build())
                     .keySchema(KeySchemaElement.builder().attributeName("hostId").keyType(KeyType.HASH).build());
                }
            });
            log.info("テーブルを作成しました: {}", table);
        } catch (ResourceInUseException e) {
            log.debug("テーブルは既に存在します: {}", table);
        }
    }
}
