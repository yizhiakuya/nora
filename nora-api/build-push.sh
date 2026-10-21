#!/usr/bin/env bash
# ./build-push.sh <nora-api|nora-web|all> [tag] [--full]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
TARGET=${1:?usage: ./build-push.sh <nora-api|nora-web|all> [tag] [--full]}
TAG=${2:-latest}
MODE=${3:-}
case "$TARGET" in nora-api|nora-web|all) ;; *) echo 'Unknown target'; exit 2 ;; esac
[[ "$TAG" =~ ^[a-zA-Z0-9_][a-zA-Z0-9_.-]{0,127}$ ]] || { echo 'Invalid image tag'; exit 2; }
[ -z "$MODE" ] || [ "$MODE" = '--full' ] || { echo 'Unknown build mode'; exit 2; }
for name in nora-api nora-web; do
  [ "$TARGET" = all ] || [ "$TARGET" = "$name" ] || continue
  args=()
  if [ "$name" = nora-api ] && [ "$MODE" != '--full' ]; then
    (cd "$ROOT"; mvn verify)
    args=(--build-arg ARTIFACT_STAGE=prebuilt)
  fi
  image="ghcr.io/yizhiakuya/nora/$name:$TAG"
  docker build "${args[@]}" -t "$image" "$ROOT/../$name"
  docker push "$image"
  if [ "$TAG" != latest ]; then docker tag "$image" "ghcr.io/yizhiakuya/nora/$name:latest"; docker push "ghcr.io/yizhiakuya/nora/$name:latest"; fi
done
