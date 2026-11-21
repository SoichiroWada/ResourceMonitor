#!/usr/bin/env bash
# ローカルでサーバーを起動し、AWS 上の DynamoDB のデータを読んでダッシュボードを表示します（確認用）。
# - Fargate には一切触れません。自動停止と DNS 更新は無効です（既定値のまま）。
# - エージェントは AWS 上の Fargate に送るので、このサーバーにデータは届きません（読むだけ）。
#
# 使い方（事前に aws sso login しておく）:
#   AWS_PROFILE=<SSO のプロファイル名> ./server/run-local-aws.sh
# 開く URL: http://localhost:18080/  （ユーザー admin / パスワード change-me）
set -euo pipefail
cd "$(dirname "$0")"
: "${AWS_PROFILE:?AWS_PROFILE に SSO のプロファイル名を指定してください}"

if [ ! -f target/resource-monitor-server.jar ]; then
  (cd web && npm install && npm run build)
  mvn -q -B package -DskipTests
fi

# Java の AWS SDK は SSO のプロファイルを直接読めない（sso モジュールを入れていない）ため、
# 一時的な認証情報（1 時間ほど有効）を環境変数で渡す
eval "$(aws configure export-credentials --profile "$AWS_PROFILE" --format env)"
unset AWS_PROFILE

export AWS_REGION="${AWS_REGION:-ap-northeast-1}"
export HOSTS_TABLE=ResourceMonitor-Hosts
export METRICS_TABLE=ResourceMonitor-Metrics
export SERVER_PORT="${SERVER_PORT:-18080}"
echo "起動中… http://localhost:${SERVER_PORT}/ （admin / change-me）  停止は Ctrl-C"
exec java -jar target/resource-monitor-server.jar
