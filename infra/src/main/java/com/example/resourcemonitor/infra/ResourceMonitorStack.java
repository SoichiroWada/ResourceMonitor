package com.example.resourcemonitor.infra;

import java.util.List;
import java.util.Map;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.Fn;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.aws_apigatewayv2_authorizers.HttpUserPoolAuthorizer;
import software.amazon.awscdk.aws_apigatewayv2_authorizers.HttpUserPoolAuthorizerProps;
import software.amazon.awscdk.aws_apigatewayv2_integrations.HttpLambdaIntegration;
import software.amazon.awscdk.services.apigatewayv2.AddRoutesOptions;
import software.amazon.awscdk.services.apigatewayv2.CorsHttpMethod;
import software.amazon.awscdk.services.apigatewayv2.CorsPreflightOptions;
import software.amazon.awscdk.services.apigatewayv2.HttpApi;
import software.amazon.awscdk.services.apigatewayv2.HttpMethod;
import software.amazon.awscdk.services.cloudfront.BehaviorOptions;
import software.amazon.awscdk.services.cloudfront.Distribution;
import software.amazon.awscdk.services.cloudfront.ViewerProtocolPolicy;
import software.amazon.awscdk.services.cloudfront.origins.S3BucketOrigin;
import software.amazon.awscdk.services.cognito.AccountRecovery;
import software.amazon.awscdk.services.cognito.CfnUserPoolUser;
import software.amazon.awscdk.services.cognito.CognitoDomainOptions;
import software.amazon.awscdk.services.cognito.OAuthFlows;
import software.amazon.awscdk.services.cognito.OAuthScope;
import software.amazon.awscdk.services.cognito.OAuthSettings;
import software.amazon.awscdk.services.cognito.SignInAliases;
import software.amazon.awscdk.services.cognito.UserPool;
import software.amazon.awscdk.services.cognito.UserPoolClient;
import software.amazon.awscdk.services.cognito.UserPoolDomain;
import software.amazon.awscdk.services.dynamodb.Attribute;
import software.amazon.awscdk.services.dynamodb.AttributeType;
import software.amazon.awscdk.services.dynamodb.BillingMode;
import software.amazon.awscdk.services.dynamodb.Table;
import software.amazon.awscdk.services.ec2.Peer;
import software.amazon.awscdk.services.ec2.Port;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.SubnetConfiguration;
import software.amazon.awscdk.services.ec2.SubnetSelection;
import software.amazon.awscdk.services.ec2.SubnetType;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.ecr.assets.Platform;
import software.amazon.awscdk.services.ecs.AssetImageProps;
import software.amazon.awscdk.services.ecs.AwsLogDriverProps;
import software.amazon.awscdk.services.ecs.Cluster;
import software.amazon.awscdk.services.ecs.ContainerDefinitionOptions;
import software.amazon.awscdk.services.ecs.ContainerImage;
import software.amazon.awscdk.services.ecs.CpuArchitecture;
import software.amazon.awscdk.services.ecs.DeploymentCircuitBreaker;
import software.amazon.awscdk.services.ecs.FargateService;
import software.amazon.awscdk.services.ecs.FargateTaskDefinition;
import software.amazon.awscdk.services.ecs.LogDriver;
import software.amazon.awscdk.services.ecs.OperatingSystemFamily;
import software.amazon.awscdk.services.ecs.PortMapping;
import software.amazon.awscdk.services.ecs.RuntimePlatform;
import software.amazon.awscdk.services.ecs.Secret;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.route53.PublicHostedZone;
import software.amazon.awscdk.services.s3.BlockPublicAccess;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.deployment.BucketDeployment;
import software.amazon.awscdk.services.s3.deployment.Source;
import software.amazon.awscdk.services.ssm.SecureStringParameterAttributes;
import software.amazon.awscdk.services.ssm.StringParameter;
import software.constructs.Construct;

/**
 * Resource Monitor 一式。
 *
 * <ul>
 *   <li>VPC（パブリックサブネットのみ・NAT なし＝無料）</li>
 *   <li>DynamoDB: Hosts / Metrics（オンデマンド、TTL 付き）</li>
 *   <li>ECS Fargate サービス（ALB なし・パブリック IP・desiredCount 0 で作成）</li>
 *   <li>Route 53 ホストゾーン（サブドメインを委任して使用）</li>
 *   <li>Cognito + S3/CloudFront のスイッチ画面 + API Gateway + Lambda</li>
 * </ul>
 */
public class ResourceMonitorStack extends Stack {

    // 循環参照を避けるため、クラスタ名・サービス名は固定値にしています
    static final String CLUSTER_NAME = "resource-monitor";
    static final String SERVICE_NAME = "resource-monitor-server";
    static final int APP_PORT = 8080;

    public ResourceMonitorStack(Construct scope, String id, StackProps props) {
        super(scope, id, props);

        String zoneName = ctx("zoneName");
        String cognitoPrefix = ctx("cognitoDomainPrefix");
        String adminEmail = ctx("adminEmail");
        String apiKeysParam = ctx("agentApiKeysParam");
        String passwordParam = ctx("dashboardPasswordParam");
        String recordName = "api." + zoneName;
        String dashboardUrl = "http://" + recordName + ":" + APP_PORT + "/";
        String serviceArn = "arn:%s:ecs:%s:%s:service/%s/%s".formatted(
                getPartition(), getRegion(), getAccount(), CLUSTER_NAME, SERVICE_NAME);

        // ---------- ネットワーク ----------
        Vpc vpc = Vpc.Builder.create(this, "Vpc")
                .maxAzs(2)
                .natGateways(0)
                .subnetConfiguration(List.of(SubnetConfiguration.builder()
                        .name("public").subnetType(SubnetType.PUBLIC).cidrMask(24).build()))
                .build();

        SecurityGroup appSg = SecurityGroup.Builder.create(this, "AppSg")
                .vpc(vpc).description("Resource Monitor server").allowAllOutbound(true).build();
        appSg.addIngressRule(Peer.anyIpv4(), Port.tcp(APP_PORT), "agents and dashboard");

        // ---------- DynamoDB ----------
        Table hosts = Table.Builder.create(this, "HostsTable")
                .tableName("ResourceMonitor-Hosts")
                .partitionKey(Attribute.builder().name("hostId").type(AttributeType.STRING).build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .removalPolicy(RemovalPolicy.DESTROY) // テスト用。データを残したい場合は RETAIN に
                .build();

        Table metrics = Table.Builder.create(this, "MetricsTable")
                .tableName("ResourceMonitor-Metrics")
                .partitionKey(Attribute.builder().name("hostId").type(AttributeType.STRING).build())
                .sortKey(Attribute.builder().name("ts").type(AttributeType.NUMBER).build())
                .timeToLiveAttribute("expiresAt")
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        // ---------- Route 53（お名前.com からサブドメインを委任） ----------
        PublicHostedZone zone = PublicHostedZone.Builder.create(this, "Zone").zoneName(zoneName).build();

        // ---------- ECS / Fargate ----------
        Cluster cluster = Cluster.Builder.create(this, "Cluster").clusterName(CLUSTER_NAME).vpc(vpc).build();

        FargateTaskDefinition taskDef = FargateTaskDefinition.Builder.create(this, "TaskDef")
                .cpu(256)
                .memoryLimitMiB(512)
                .runtimePlatform(RuntimePlatform.builder()
                        .cpuArchitecture(CpuArchitecture.X86_64)
                        .operatingSystemFamily(OperatingSystemFamily.LINUX).build())
                .build();

        taskDef.addContainer("app", ContainerDefinitionOptions.builder()
                .image(ContainerImage.fromAsset("../server",
                        AssetImageProps.builder().platform(Platform.LINUX_AMD64).build()))
                .portMappings(List.of(PortMapping.builder().containerPort(APP_PORT).build()))
                .logging(LogDriver.awsLogs(AwsLogDriverProps.builder()
                        .streamPrefix("server").logRetention(RetentionDays.ONE_WEEK).build()))
                .environment(Map.of(
                        "HOSTS_TABLE", hosts.getTableName(),
                        "METRICS_TABLE", metrics.getTableName(),
                        "AUTO_STOP_ENABLED", "true",
                        "AUTO_STOP_IDLE_MINUTES", "30",
                        "ECS_CLUSTER", CLUSTER_NAME,
                        "ECS_SERVICE", SERVICE_NAME,
                        "DNS_ENABLED", "true",
                        "DNS_HOSTED_ZONE_ID", zone.getHostedZoneId(),
                        "DNS_RECORD_NAME", recordName))
                // SSM Parameter Store の SecureString（事前に作成しておく。README 参照）
                .secrets(Map.of(
                        "AGENT_API_KEYS", Secret.fromSsmParameter(StringParameter.fromSecureStringParameterAttributes(
                                this, "AgentKeysParam",
                                SecureStringParameterAttributes.builder().parameterName(apiKeysParam).build())),
                        "DASHBOARD_PASSWORD", Secret.fromSsmParameter(StringParameter.fromSecureStringParameterAttributes(
                                this, "DashboardPasswordParam",
                                SecureStringParameterAttributes.builder().parameterName(passwordParam).build()))))
                .build());

        // タスクロール: DynamoDB 読み書き / 自分自身の停止 / DNS 更新
        hosts.grantReadWriteData(taskDef.getTaskRole());
        metrics.grantReadWriteData(taskDef.getTaskRole());
        taskDef.getTaskRole().addToPrincipalPolicy(PolicyStatement.Builder.create()
                .actions(List.of("ecs:UpdateService")).resources(List.of(serviceArn)).build());
        taskDef.getTaskRole().addToPrincipalPolicy(PolicyStatement.Builder.create()
                .actions(List.of("route53:ChangeResourceRecordSets")).resources(List.of(zone.getHostedZoneArn())).build());

        FargateService.Builder.create(this, "Service")
                .serviceName(SERVICE_NAME)
                .cluster(cluster)
                .taskDefinition(taskDef)
                .desiredCount(0) // 普段は停止。スイッチ画面から 1 にする（cdk deploy するたびに 0 に戻ります）
                .minHealthyPercent(0)
                .maxHealthyPercent(200)
                .circuitBreaker(DeploymentCircuitBreaker.builder().enable(true).rollback(false).build())
                .assignPublicIp(true)
                .vpcSubnets(SubnetSelection.builder().subnetType(SubnetType.PUBLIC).build())
                .securityGroups(List.of(appSg))
                .build();

        // ---------- スイッチ画面（S3 + CloudFront） ----------
        Bucket webBucket = Bucket.Builder.create(this, "SwitchWebBucket")
                .blockPublicAccess(BlockPublicAccess.BLOCK_ALL)
                .enforceSsl(true)
                .removalPolicy(RemovalPolicy.DESTROY)
                .autoDeleteObjects(true)
                .build();

        Distribution cdn = Distribution.Builder.create(this, "SwitchWebCdn")
                .defaultRootObject("index.html")
                .defaultBehavior(BehaviorOptions.builder()
                        .origin(S3BucketOrigin.withOriginAccessControl(webBucket))
                        .viewerProtocolPolicy(ViewerProtocolPolicy.REDIRECT_TO_HTTPS)
                        .build())
                .build();
        String switchOrigin = "https://" + cdn.getDistributionDomainName();
        String switchUrl = switchOrigin + "/";

        // ---------- Cognito ----------
        UserPool userPool = UserPool.Builder.create(this, "UserPool")
                .userPoolName("resource-monitor-users")
                .selfSignUpEnabled(false) // 管理者が作成したユーザーだけログイン可
                .signInAliases(SignInAliases.builder().email(true).build())
                .accountRecovery(AccountRecovery.EMAIL_ONLY)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();

        UserPoolDomain domain = userPool.addDomain("Domain", software.amazon.awscdk.services.cognito.UserPoolDomainOptions.builder()
                .cognitoDomain(CognitoDomainOptions.builder().domainPrefix(cognitoPrefix).build())
                .build());

        UserPoolClient client = userPool.addClient("SwitchClient", software.amazon.awscdk.services.cognito.UserPoolClientOptions.builder()
                .generateSecret(false) // ブラウザ（SPA）用なのでシークレットなし + PKCE
                .oAuth(OAuthSettings.builder()
                        .flows(OAuthFlows.builder().authorizationCodeGrant(true).build())
                        .scopes(List.of(OAuthScope.OPENID, OAuthScope.EMAIL))
                        .callbackUrls(List.of(switchUrl))
                        .logoutUrls(List.of(switchUrl))
                        .build())
                .idTokenValidity(Duration.hours(1))
                .build());

        if (!adminEmail.isBlank()) {
            // 初回だけ: 仮パスワードがメールで届きます
            CfnUserPoolUser.Builder.create(this, "AdminUser")
                    .userPoolId(userPool.getUserPoolId())
                    .username(adminEmail)
                    .userAttributes(List.of(
                            CfnUserPoolUser.AttributeTypeProperty.builder().name("email").value(adminEmail).build(),
                            CfnUserPoolUser.AttributeTypeProperty.builder().name("email_verified").value("true").build()))
                    .desiredDeliveryMediums(List.of("EMAIL"))
                    .build();
        }

        // ---------- 起動/停止 Lambda + HTTP API ----------
        Function switchFn = Function.Builder.create(this, "SwitchFunction")
                .runtime(Runtime.JAVA_21)
                .handler("com.example.resourcemonitor.sw.SwitchHandler::handleRequest")
                .code(Code.fromAsset("../switch-lambda/target/switch-lambda.jar"))
                .memorySize(512)
                .timeout(Duration.seconds(20))
                .logGroup(LogGroup.Builder.create(this, "SwitchFunctionLogs")
                        .retention(RetentionDays.ONE_WEEK).removalPolicy(RemovalPolicy.DESTROY).build())
                .environment(Map.of(
                        "ECS_CLUSTER", CLUSTER_NAME,
                        "ECS_SERVICE", SERVICE_NAME,
                        "DASHBOARD_URL", dashboardUrl))
                .build();
        switchFn.addToRolePolicy(PolicyStatement.Builder.create()
                .actions(List.of("ecs:UpdateService", "ecs:DescribeServices"))
                .resources(List.of(serviceArn)).build());

        HttpApi api = HttpApi.Builder.create(this, "SwitchApi")
                .apiName("resource-monitor-switch")
                .corsPreflight(CorsPreflightOptions.builder()
                        .allowOrigins(List.of(switchOrigin))
                        .allowMethods(List.of(CorsHttpMethod.GET, CorsHttpMethod.POST, CorsHttpMethod.OPTIONS))
                        .allowHeaders(List.of("Authorization", "Content-Type"))
                        .maxAge(Duration.hours(1))
                        .build())
                .build();

        HttpUserPoolAuthorizer authorizer = new HttpUserPoolAuthorizer("CognitoAuthorizer", userPool,
                HttpUserPoolAuthorizerProps.builder().userPoolClients(List.of(client)).build());
        HttpLambdaIntegration integration = new HttpLambdaIntegration("SwitchIntegration", switchFn);

        api.addRoutes(AddRoutesOptions.builder().path("/status").methods(List.of(HttpMethod.GET))
                .integration(integration).authorizer(authorizer).build());
        api.addRoutes(AddRoutesOptions.builder().path("/start").methods(List.of(HttpMethod.POST))
                .integration(integration).authorizer(authorizer).build());
        api.addRoutes(AddRoutesOptions.builder().path("/stop").methods(List.of(HttpMethod.POST))
                .integration(integration).authorizer(authorizer).build());

        // スイッチ画面の配置 + config.json の生成（デプロイ時の実際の値が埋め込まれる）
        BucketDeployment.Builder.create(this, "SwitchWebDeploy")
                .destinationBucket(webBucket)
                .distribution(cdn)
                .sources(List.of(
                        Source.asset("../switch-web"),
                        Source.jsonData("config.json", Map.of(
                                "cognitoDomain", domain.baseUrl(),
                                "clientId", client.getUserPoolClientId(),
                                "redirectUri", switchUrl,
                                "apiUrl", api.getApiEndpoint()))))
                .build();

        // ---------- 出力 ----------
        CfnOutput.Builder.create(this, "SwitchUrl").value(switchUrl)
                .description("起動/停止スイッチ画面").build();
        CfnOutput.Builder.create(this, "DashboardUrl").value(dashboardUrl)
                .description("ダッシュボード（Basic 認証）").build();
        CfnOutput.Builder.create(this, "AgentServerUrl").value("http://" + recordName + ":" + APP_PORT + "/api/metrics")
                .description("エージェントの serverUrl に設定する値").build();
        CfnOutput.Builder.create(this, "NameServers").value(Fn.join(",", zone.getHostedZoneNameServers()))
                .description("お名前.com で NS レコード（ホスト名: サブドメイン部分）として登録する 4 つの値").build();
        CfnOutput.Builder.create(this, "UserPoolId").value(userPool.getUserPoolId()).build();
    }

    private String ctx(String key) {
        Object v = getNode().tryGetContext(key);
        return v == null ? "" : v.toString();
    }
}
