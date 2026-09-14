#!/usr/bin/env bash
# 从 megumin 卸载 Nora 线上部署（开发未完成，撤下生产环境）。
#
# 安全设计：
#   - 默认 dry-run，必须显式 --apply 才动手
#   - 动手前先备份 PostgreSQL 数据 + agent-workspace 到本机存档
#   - **绝不碰 phone-album**：8900 端口上与 nora.rainaki.top 同端口共存的是
#     home.rainaki.top（手机相册中继），只删 nora 自己的 server 块与软链
#   - 容器/卷只删 nora-* 前缀，且逐个确认归属（其他项目有各自的 nacos/postgres）
#
# 用法:
#   bash scripts/teardown-megumin.sh            # 预览将删除什么
#   bash scripts/teardown-megumin.sh --apply    # 备份后执行
set -uo pipefail

APPLY=0
[ "${1:-}" = "--apply" ] && APPLY=1

ROOT=/d/claude/Nora
ARCHIVE="$ROOT/archive/megumin-teardown"
STAMP=$(date +%Y%m%d-%H%M%S)

SERVICES="nora-gateway nora-file nora-rag nora-agent nora-datasource nora-env nora-automation"
CONTAINERS="nora-postgres nora-redis nora-nacos"
VOLUMES="nora-pgdata nora-nacos-data"

echo "=== 将卸载的内容 ==="
echo "systemd : $SERVICES"
echo "容器    : $CONTAINERS"
echo "卷      : $VOLUMES"
echo "nginx   : sites-enabled/nora -> sites-available/nora（保留 phone-album）"
echo "文件    : /opt/nora  /var/www/nora  /var/www/nora.bak-*  /var/www/nora-backup-*"
echo "凭据    : /etc/nginx/nora.htpasswd"

if [ "$APPLY" -eq 0 ]; then
  echo ""
  echo "=== 预览：当前状态 ==="
  ssh -o BatchMode=yes megumin "systemctl is-active $SERVICES 2>&1 | sort | uniq -c; echo '--- 容器 ---'; docker ps --format '{{.Names}}' | grep '^nora-' || echo '(无)'" 2>&1
  echo ""
  echo "加 --apply 执行（会先备份数据到 $ARCHIVE）"
  exit 0
fi

# ---------- 阶段 1：备份 ----------
echo ""
echo "=== 阶段 1/4：备份数据（本地存档）==="
mkdir -p "$ARCHIVE"

echo "-> PostgreSQL 全量 dump"
ssh -o BatchMode=yes megumin "docker exec nora-postgres pg_dumpall -U nora > /tmp/nora-teardown-$STAMP.sql && gzip -f /tmp/nora-teardown-$STAMP.sql && ls -la /tmp/nora-teardown-$STAMP.sql.gz" || {
  echo "!! 备份失败，终止（不删除任何东西）"; exit 1; }
scp -q "megumin:/tmp/nora-teardown-$STAMP.sql.gz" "$ARCHIVE/" || {
  echo "!! 下载备份失败，终止"; exit 1; }

echo "-> agent-workspace"
ssh -o BatchMode=yes megumin "cd /opt/nora && tar czf /tmp/nora-ws-$STAMP.tgz agent-workspace 2>/dev/null && ls -la /tmp/nora-ws-$STAMP.tgz" || {
  echo "!! workspace 打包失败，终止"; exit 1; }
scp -q "megumin:/tmp/nora-ws-$STAMP.tgz" "$ARCHIVE/" || {
  echo "!! 下载失败，终止"; exit 1; }

echo "-> 校验备份可读"
gzip -t "$ARCHIVE/nora-teardown-$STAMP.sql.gz" && echo "   dump 完整"
tar tzf "$ARCHIVE/nora-ws-$STAMP.tgz" >/dev/null && echo "   workspace 完整"
ls -la "$ARCHIVE/"

# ---------- 阶段 2：停服务 ----------
echo ""
echo "=== 阶段 2/4：停止并禁用 systemd 服务 ==="
for s in $SERVICES; do
  echo "-> $s"
  ssh -o BatchMode=yes megumin "systemctl disable --now $s 2>&1 | tail -1"
done

# ---------- 阶段 3：删容器与卷 ----------
echo ""
echo "=== 阶段 3/4：删除容器与卷 ==="
for c in $CONTAINERS; do
  echo "-> $c"
  ssh -o BatchMode=yes megumin "docker rm -f $c >/dev/null 2>&1 && echo removed || echo '(不存在)'"
done
for v in $VOLUMES; do
  echo "-> 卷 $v"
  ssh -o BatchMode=yes megumin "docker volume rm $v >/dev/null 2>&1 && echo removed || echo '(占用或不存在)'"
done

# ---------- 阶段 4：删文件与 nginx ----------
echo ""
echo "=== 阶段 4/4：删除文件与 nginx 配置 ==="
ssh -o BatchMode=yes megumin "
set -x
# nginx：只移除 nora 站点，先测配置再 reload
rm -f /etc/nginx/sites-enabled/nora
rm -f /etc/nginx/sites-available/nora
rm -f /etc/nginx/nora.htpasswd
nginx -t && systemctl reload nginx

# 服务单元
rm -f /etc/systemd/system/nora-*.service
systemctl daemon-reload

# 文件
rm -rf /opt/nora
rm -rf /var/www/nora /var/www/nora.bak-* /var/www/nora-backup-*
rm -f /tmp/nora-teardown-$STAMP.sql.gz /tmp/nora-ws-$STAMP.tgz
" 2>&1 | grep -vE '^\+\+? (set -x|\s*)$' | tail -20

echo ""
echo "=== 完成。验证 ==="
echo "-> 残留的 nora 进程/单元（应为空）"
ssh -o BatchMode=yes megumin "systemctl list-units --all 2>/dev/null | grep nora || echo '(无)'" 2>&1
ssh -o BatchMode=yes megumin "docker ps -a --format '{{.Names}}' | grep -E '^nora-' || echo '(无容器)'" 2>&1
echo "-> phone-album 必须仍然正常"
curl -sS --noproxy '*' --max-time 20 "https://home.rainaki.top:8900/health" || echo "!! phone-album 异常，请检查"
echo ""
echo "备份存档: $ARCHIVE"
