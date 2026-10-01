#!/usr/bin/env bash
# 本地构建并推送镜像到 GHCR(比走 GitHub Actions 快得多——增量构建秒级)
#
# 用法:
#   ./build-push.sh agent-service          # 构建并推送单个服务
#   ./build-push.sh agent-service v1.2.0   # 指定 tag(同时推 latest)
#   ./build-push.sh all                    # 全部 8 个后端服务 + 前端
#
# 前置:docker login ghcr.io(首次:gh auth token | docker login ghcr.io -u <user> --password-stdin)
set -euo pipefail

REGISTRY=ghcr.io/yizhiakuya/nora
ROOT="$(cd "$(dirname "$0")" && pwd)"

build_backend() {
  local svc=$1 tag=$2
  echo "=== 构建 $svc (tag: $tag) ..."
  docker build -f "$ROOT/Dockerfile" --build-arg SERVICE="$svc" -t "$REGISTRY/$svc:$tag" "$ROOT"
  [ "$tag" != "latest" ] && docker tag "$REGISTRY/$svc:$tag" "$REGISTRY/$svc:latest"
  docker push "$REGISTRY/$svc:$tag"
  [ "$tag" != "latest" ] && docker push "$REGISTRY/$svc:latest"
  echo "=== $svc 推送完成"
}

build_web() {
  local tag=$1
  echo "=== 构建 nora-web (tag: $tag) ..."
  docker build -t "$REGISTRY/nora-web:$tag" "$ROOT/../nora-web"
  [ "$tag" != "latest" ] && docker tag "$REGISTRY/nora-web:$tag" "$REGISTRY/nora-web:latest"
  docker push "$REGISTRY/nora-web:$tag"
  [ "$tag" != "latest" ] && docker push "$REGISTRY/nora-web:latest"
  echo "=== nora-web 推送完成"
}

TARGET=${1:?usage: ./build-push.sh <service|all> [tag]}
TAG=${2:-latest}

if [ "$TARGET" = "all" ]; then
  for svc in gateway-service file-service rag-service agent-service datasource-service env-service automation-service notification-service; do
    build_backend "$svc" "$TAG"
  done
  build_web "$TAG"
else
  if [ "$TARGET" = "nora-web" ]; then
    build_web "$TAG"
  else
    build_backend "$TARGET" "$TAG"
  fi
fi

echo ""
echo "完成。部署端更新:ssh root@<host> 'cd /opt/nora/repo && git pull && cd nora-api && docker compose -f docker-compose.prod.yml pull && docker compose -f docker-compose.prod.yml up -d'"
