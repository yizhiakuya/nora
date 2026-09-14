#!/usr/bin/env bash
# 把构建好的前端 dist 发布到 megumin 的静态目录（带时间戳备份）。
#
# 为什么要脚本：在 PowerShell 里内联 ssh "...$(date ...)..." 时，$(...) 会被
# PowerShell 抢先求值（报 "Cannot bind parameter 'Date'"），复杂引号也容易被拦。
# 把这些放进 .sh、经 bash 执行，行为可预期。
#
# 用法: bash scripts/deploy-web.sh
set -euo pipefail

DIST=/d/claude/Nora/nora-web/dist
REMOTE=/var/www/nora
STAMP=$(date +%Y%m%d-%H%M%S)

if [ ! -d "$DIST" ]; then
  echo "dist 不存在，请先 pnpm run build: $DIST" >&2
  exit 1
fi

echo "== 备份现有 $REMOTE -> $REMOTE.bak-$STAMP =="
ssh -o BatchMode=yes megumin "cp -r $REMOTE $REMOTE.bak-$STAMP"

echo "== 上传新构建 =="
# 只传构建产物：index.html + assets（图标等静态文件保留在服务器上）
scp -q -r "$DIST/index.html" "megumin:$REMOTE/index.html"
scp -q -r "$DIST/assets" "megumin:$REMOTE/"

echo "== 校验 =="
ssh -o BatchMode=yes megumin "ls -la $REMOTE/index.html && ls $REMOTE/assets | head -5 && ls $REMOTE/assets | wc -l"
echo "部署完成（备份：$REMOTE.bak-$STAMP）"
