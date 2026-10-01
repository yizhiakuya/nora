#!/usr/bin/env bash
# 本地构建并推送镜像到 GHCR(比走 GitHub Actions 快得多——增量构建秒级)
#
# 用法:
#   ./build-push.sh agent-service          # 构建并推送单个服务
#   ./build-push.sh agent-service v1.2.0   # 指定 tag(同时推 latest)
#   ./build-push.sh all                    # 全部 8 个后端服务 + 前端
#   ./build-push.sh agent-service --full   # 强制走主 Dockerfile(容器内 Maven 全量构建)
#
# 前置:docker login ghcr.io(首次:gh auth token | docker login ghcr.io -u <user> --password-stdin)
#
# 构建策略(2026-10-02):
#   默认走「预构建」路径——本地 mvn package(依赖已缓存,秒级)→ Dockerfile.prebuilt
#   只打包 jar(秒级)。此前主 Dockerfile 在容器内跑 Maven,而 .dockerignore 排除
#   target/,任何源码改动都触发容器内全量依赖下载(国内网络实测卡 40 分钟+)。
#   --full 走主 Dockerfile(CI/无本地 Maven 环境时用)。
set -euo pipefail

REGISTRY=ghcr.io/yizhiakuya/nora
ROOT="$(cd "$(dirname "$0")" && pwd)"

# 预构建路径:本地 mvn package + 轻量 Dockerfile(仅打包 jar)
build_backend_prebuilt() {
  local svc=$1 tag=$2
  echo "=== [prebuilt] 本地打包 $svc ..."
  (cd "$ROOT" && mvn -q -pl "services/$svc" package -DskipTests -o)
  echo "=== [prebuilt] 组装镜像 $svc (tag: $tag) ..."
  # 临时切换 dockerignore:预构建需要 target/ 里的 jar(构建后恢复)
  cp "$ROOT/.dockerignore" "$ROOT/.dockerignore.bak"
  cp "$ROOT/.dockerignore.prebuilt" "$ROOT/.dockerignore"
  trap 'mv "$ROOT/.dockerignore.bak" "$ROOT/.dockerignore"' RETURN
  docker build -f "$ROOT/Dockerfile.prebuilt" --build-arg SERVICE="$svc" -t "$REGISTRY/$svc:$tag" "$ROOT"
  mv "$ROOT/.dockerignore.bak" "$ROOT/.dockerignore"
  trap - RETURN
  [ "$tag" != "latest" ] && docker tag "$REGISTRY/$svc:$tag" "$REGISTRY/$svc:latest"
  docker push "$REGISTRY/$svc:$tag"
  [ "$tag" != "latest" ] && docker push "$REGISTRY/$svc:latest"
  echo "=== $svc 推送完成"
}

# 全量路径:容器内 Maven 构建(主 Dockerfile)
build_backend_full() {
  local svc=$1 tag=$2
  echo "=== [full] 构建 $svc (tag: $tag) ..."
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

TARGET=${1:?usage: ./build-push.sh <service|all> [tag] [--full]}
TAG=${2:-latest}
MODE=${3:-}

if [ "$TARGET" = "all" ]; then
  for svc in gateway-service file-service rag-service agent-service datasource-service env-service automation-service notification-service; do
    if [ "$MODE" = "--full" ]; then build_backend_full "$svc" "$TAG"; else build_backend_prebuilt "$svc" "$TAG"; fi
  done
  build_web "$TAG"
else
  if [ "$TARGET" = "nora-web" ]; then
    build_web "$TAG"
  else
    if [ "$MODE" = "--full" ]; then build_backend_full "$TARGET" "$TAG"; else build_backend_prebuilt "$TARGET" "$TAG"; fi
  fi
fi

echo ""
echo "完成。部署端更新:ssh root@<host> 'cd /opt/nora/repo && git pull && cd nora-api && docker compose -f docker-compose.prod.yml pull && docker compose -f docker-compose.prod.yml up -d'"
