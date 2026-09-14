#!/usr/bin/env bash
# 清理 megumin 上累积的部署残留。
#
# 判断依据（不做模糊删除）：
#   1. /var/www/nora/assets 里未被当前 index.html / 当前 bundle 引用的旧构建
#      （前端资源名带内容哈希，旧文件永远不会再被请求）
#   2. /var/www/nora.bak-* 部署备份：保留最近 1 份，其余清掉
#   3. APK 留档：只保留最新 2 个版本 + latest 软链（旧版无人再下）
#   4. relay 的 .bak 文件：留最新 1 份
#
# 用法: bash scripts/cleanup-megumin.sh [--apply]
set -euo pipefail

APPLY=0
[ "${1:-}" = "--apply" ] && APPLY=1

run() {
  # 统一在远端执行；dry-run 时只报告
  ssh -o BatchMode=yes megumin "$1"
}

echo "=== 1. 前端旧构建残留（未被当前版本引用）==="
run '
cd /var/www/nora
current_js=$(grep -oE "assets/index-[A-Za-z0-9_-]+\.js" index.html | head -1 | sed "s#assets/##")
current_css=$(grep -oE "assets/index-[A-Za-z0-9_-]+\.css" index.html | head -1 | sed "s#assets/##")
# 动态 chunk：当前 js 里引用到的 MarkdownContent 等
dyn=$(grep -oE "MarkdownContent-[A-Za-z0-9_-]+\.js" "assets/$current_js" | sort -u | tr "\n" " ")
echo "  当前引用: $current_js  $current_css  动态: $dyn"
for f in assets/*; do
  b=$(basename "$f")
  keep=0
  [ "$b" = "$current_js" ] && keep=1
  [ "$b" = "$current_css" ] && keep=1
  for d in $dyn; do [ "$b" = "$d" ] && keep=1; done
  if [ $keep -eq 0 ]; then
    echo "  旧构建: $b ($(stat -c%s "$f") bytes)"
  fi
done
'

echo ""
echo "=== 2. 部署备份（保留最近 1 份）==="
run 'ls -d /var/www/nora.bak-* 2>/dev/null | sort | head -n -1 || true'

echo ""
echo "=== 3. APK 留档（保留最新 2 个 + latest）==="
run 'ls /var/www/phone-album/phonealbum-[0-9]*.apk 2>/dev/null | sort -V | head -n -2 || true'

echo ""
echo "=== 4. relay 备份（保留最新 1 份）==="
run 'ls /root/phone-album-relay/relay.mjs.bak* 2>/dev/null | sort | head -n -1 || true'

if [ "$APPLY" -eq 0 ]; then
  echo ""
  echo "=== 预览模式：加 --apply 执行删除 ==="
  exit 0
fi

echo ""
echo "=== 执行清理 ==="
run '
cd /var/www/nora
current_js=$(grep -oE "assets/index-[A-Za-z0-9_-]+\.js" index.html | head -1 | sed "s#assets/##")
current_css=$(grep -oE "assets/index-[A-Za-z0-9_-]+\.css" index.html | head -1 | sed "s#assets/##")
dyn=$(grep -oE "MarkdownContent-[A-Za-z0-9_-]+\.js" "assets/$current_js" | sort -u | tr "\n" " ")
for f in assets/*; do
  b=$(basename "$f")
  keep=0
  [ "$b" = "$current_js" ] && keep=1
  [ "$b" = "$current_css" ] && keep=1
  for d in $dyn; do [ "$b" = "$d" ] && keep=1; done
  [ $keep -eq 0 ] && rm -f "$f" && echo "  删除旧构建: $b"
done
# 备份留最近 1 份
ls -d /var/www/nora.bak-* 2>/dev/null | sort | head -n -1 | xargs -r rm -rf
echo "  已清理旧备份（保留最近 1 份）"
# APK 留最新 2 个
ls /var/www/phone-album/phonealbum-[0-9]*.apk 2>/dev/null | sort -V | head -n -2 | xargs -r rm -f
echo "  已清理旧 APK（保留最新 2 个）"
# relay 备份留 1
ls /root/phone-album-relay/relay.mjs.bak* 2>/dev/null | sort | head -n -1 | xargs -r rm -f
echo "  已清理 relay 旧备份"
'
echo "清理完成。"
