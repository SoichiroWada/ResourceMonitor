# ---- ダッシュボード（React）をビルド ----
FROM node:24-alpine AS web
WORKDIR /src/web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
# vite.config.ts の outDir により /src/src/main/resources/static に出力される
RUN npm run build

# ---- サーバー（Spring Boot）をビルド ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
COPY --from=web /src/src/main/resources/static ./src/main/resources/static
RUN mvn -q -B package -DskipTests

# ---- 実行 ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/target/resource-monitor-server.jar app.jar
RUN useradd --system --uid 10001 app
USER app
EXPOSE 8080
# 0.5GB メモリのタスク向けに控えめなヒープ設定
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/app.jar"]
