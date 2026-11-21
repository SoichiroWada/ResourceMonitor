#!/usr/bin/env bash
# Ubuntu (amd64/arm64) と Windows 11 (amd64) 用のバイナリをまとめて作ります。
set -euo pipefail
cd "$(dirname "$0")"
VERSION="${VERSION:-$(date +%Y%m%d)}"
LDFLAGS="-s -w -X main.version=${VERSION}"
mkdir -p dist

# Windows 用のアイコンとバージョン情報（.syso）を生成。go build が Windows 向けのときだけ自動で取り込む。
# go-winres が無ければ: go install github.com/tc-hib/go-winres@latest
# 例: VERSION=20261121 → ファイルバージョン 2026.11.21.0
WINVER="${VERSION:0:4}.$((10#${VERSION:4:2})).$((10#${VERSION:6:2})).0"
go-winres make --in cmd/agent/winres/winres.json --out cmd/agent/rsrc --arch amd64 \
  --file-version "$WINVER" --product-version "$WINVER"

CGO_ENABLED=0 GOOS=linux   GOARCH=amd64 go build -ldflags "$LDFLAGS" -o dist/resource-agent-linux-amd64   ./cmd/agent
CGO_ENABLED=0 GOOS=linux   GOARCH=arm64 go build -ldflags "$LDFLAGS" -o dist/resource-agent-linux-arm64   ./cmd/agent
CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go build -ldflags "$LDFLAGS" -o dist/resource-agent-windows-amd64.exe ./cmd/agent
ls -l dist
