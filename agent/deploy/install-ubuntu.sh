#!/usr/bin/env bash
# Ubuntu Server へのインストール（root で実行）
#   sudo ./install-ubuntu.sh ./resource-agent-linux-amd64 ./config.json
set -euo pipefail
BIN="${1:?バイナリのパスを指定してください}"
CFG="${2:?config.json のパスを指定してください}"
install -d -m 755 /opt/resource-agent
install -m 755 "$BIN" /opt/resource-agent/resource-agent
install -m 600 "$CFG" /opt/resource-agent/config.json
/opt/resource-agent/resource-agent -config /opt/resource-agent/config.json install   # systemd ユニットを作成
/opt/resource-agent/resource-agent start
systemctl status resource-agent --no-pager || true
echo "ログ確認: journalctl -u resource-agent -f"
