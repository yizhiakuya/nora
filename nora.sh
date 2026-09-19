#!/usr/bin/env bash
# Nora 后端开发环境一键管理脚本
# 用法: ./nora.sh <start|stop|restart|status|build|deploy> [服务名|all]
#   start            启动全部 7 服务(基础设施容器需已运行)——并发拉起 + 健康轮询
#   stop             停全部——单次批量 kill(避免 N 次 powershell 冷启动)
#   restart [svc]    重启全部或单个(单个=改代码后的标准流程)
#   status           端口 + 健康一览
#   build svc        停→clean 打包→启动单个服务;build common 先 install nora-common
#   deploy [all]     一键部署最新代码(仅后端):自动检测变更→停→并行构建→并发启动→健康轮询
#                    (共享模块 common/*,api/* 变更时自动 install 并全量重建;all=强制全量)
set -u
ROOT=/d/claude/Nora/nora-api
LOGDIR=/d/claude/Nora/nora-api
PORTS=(gateway:8080 file:8081 rag:8082 agent:8083 datasource:8084 env:8085 automation:8086 notification:8087)
ALL=(gateway file rag agent datasource env automation notification)
SHARED_MODULES=common/nora-common,common/nora-security,api/rag-api,api/file-api,api/datasource-api

port_of() { echo "${PORTS[@]}" | tr ' ' '\n' | grep "^$1:" | cut -d: -f2; }

pid_on_port() { netstat -ano | grep ":$1 " | grep LISTENING | head -1 | awk '{print $NF}'; }

jar_of() { echo "$ROOT/services/$1-service/target/$1-service-0.1.0-SNAPSHOT.jar"; }

# 批量停止:单次 powershell 调用杀多个 PID(逐个调用 powershell 冷启动约 1-2s/次)
stop_many() {
  local svcs=("$@") pids=() lines=() svc p pid
  for svc in "${svcs[@]}"; do
    p=$(port_of "$svc"); pid=$(pid_on_port "$p")
    if [ -n "$pid" ]; then pids+=("$pid"); lines+=("stopped $svc (pid $pid, port $p)");
    else lines+=("$svc not running"); fi
  done
  if [ ${#pids[@]} -gt 0 ]; then
    local joined; joined=$(IFS=,; echo "${pids[*]}")
    powershell -Command "Stop-Process -Id $joined -Force -ErrorAction SilentlyContinue" 2>/dev/null
  fi
  printf '%s\n' "${lines[@]}"
}

stop_svc() { stop_many "$1"; }

# 并发启动:后台子 shell 立即返回,多个 JVM 同时拉起(不排队)
start_svc() {
  cd "$ROOT" || return 1
  (java -jar services/$1-service/target/$1-service-0.1.0-SNAPSHOT.jar \
    > "$LOGDIR/$1-service.stdout.log" 2> "$LOGDIR/$1-service.stderr.log" &)
  echo "starting $1 ..."
}

health() {
  local p=$1
  curl -sS --noproxy '*' -o /dev/null -w "%{http_code}" "http://localhost:$p/actuator/health" --max-time 5 2>/dev/null || echo -n "000"
}

# 健康轮询:全部就绪立即返回(最长 120s),替代固定 sleep(启动快时不空等)
wait_health() {
  local svcs=("$@") deadline=$((SECONDS + 120)) ok=0
  echo "等待服务就绪(最长 120s)..."
  while [ $SECONDS -lt $deadline ]; do
    ok=1
    for svc in "${svcs[@]}"; do
      [ "$(health "$(port_of "$svc")")" = "200" ] || ok=0
    done
    [ $ok -eq 1 ] && break
    sleep 3
  done
  cmd_status
  [ $ok -eq 1 ] || { echo "警告:部分服务未在 120s 内就绪,查对应 stderr.log / logs/*-text.log"; return 1; }
}

cmd_status() {
  for entry in "${PORTS[@]}"; do
    svc=${entry%%:*}; p=${entry##*:}
    listen=$(netstat -ano | grep ":$p " | grep -c LISTENING)
    printf "%-10s :%s  listen=%s  health=%s\n" "$svc" "$p" "$listen" "$(health $p)"
  done
}

cmd_stop() { stop_many "${ALL[@]}"; }

cmd_start() {
  local started=()
  for svc in "${ALL[@]}"; do
    if [ -n "$(pid_on_port $(port_of $svc))" ]; then echo "$svc already running"; else start_svc "$svc"; started+=("$svc"); fi
  done
  if [ ${#started[@]} -gt 0 ]; then wait_health "${started[@]}"; else cmd_status; fi
}

# 本服务或共享模块(common/*, api/*)的源码是否比 jar 新(或 jar 不存在)
svc_stale() {
  local svc=$1 jar newer; jar=$(jar_of "$svc")
  [ -f "$jar" ] || return 0
  newer=$(find "$ROOT/services/$svc-service/src" "$ROOT/common" "$ROOT/api" \
    -name '*.java' -newer "$jar" 2>/dev/null | head -1)
  [ -n "$newer" ]
}

# 共享模块源码是否比已安装到 ~/.m2 的包新(服务构建从这里取包)
shared_stale() {
  local m name installed newer
  for m in "$ROOT"/common/* "$ROOT"/api/*; do
    [ -d "$m" ] || continue
    name=$(basename "$m")
    installed="$HOME/.m2/repository/com/nora/$name/0.1.0-SNAPSHOT/$name-0.1.0-SNAPSHOT.jar"
    [ -f "$installed" ] || return 0
    newer=$(find "$m/src" -name '*.java' -newer "$installed" 2>/dev/null | head -1)
    [ -n "$newer" ] && return 0
  done
  return 1
}

# 基础设施容器检查(缺失则拉起;nacos 等端口就绪)
check_infra() {
  local c st i
  for c in nora-postgres nora-redis nora-nacos nora-kafka; do
    st=$(docker inspect -f '{{.State.Status}}' "$c" 2>/dev/null)
    if [ "$st" != "running" ]; then
      echo "基础设施 $c 未运行(状态:${st:-不存在}),启动中 ..."
      docker start "$c" >/dev/null 2>&1 || { echo "无法启动 $c,请手动检查 Docker"; exit 1; }
      if [ "$c" = "nora-nacos" ]; then
        i=0; while [ $i -lt 30 ] && [ -z "$(pid_on_port 8848)" ]; do sleep 2; i=$((i+1)); done
      fi
    fi
  done
  echo "基础设施 OK: nora-postgres / nora-redis / nora-nacos / nora-kafka"
}

cmd_restart_one() {
  local svc=$1
  stop_svc "$svc"
  cd "$ROOT" || exit 1
  echo "clean package $svc ..."
  mvn -q -T 1C -pl services/$svc-service clean package -DskipTests || { echo "BUILD FAILED"; exit 1; }
  start_svc "$svc"
  wait_health "$svc"
}

cmd_build() {
  local target=$1
  cd "$ROOT" || exit 1
  if [ "$target" = "common" ]; then
    stop_many "${ALL[@]}"
    mvn -q -T 1C -pl common/nora-common clean install -DskipTests || { echo "INSTALL FAILED"; exit 1; }
    echo "nora-common installed. Run './nora.sh build <svc>' per changed service, or './nora.sh start'."
    return
  fi
  cmd_restart_one "$target"
}

# 一键部署最新代码(仅后端):检测变更 → 停 → 并行构建 → 并发启动 → 健康轮询
cmd_deploy() {
  local mode=${1:-auto}
  cd "$ROOT" || exit 1
  check_infra

  local stale=()
  if [ "$mode" = "all" ]; then
    stale=("${ALL[@]}"); echo "强制全量部署 7 服务"
  else
    for svc in "${ALL[@]}"; do svc_stale "$svc" && stale+=("$svc"); done
  fi

  if [ ${#stale[@]} -gt 0 ]; then
    echo "变更服务: ${stale[*]}"
    if shared_stale; then
      echo "共享模块(common/api)有更新 → 全量重建,先 install 到本地仓库 ..."
      stop_many "${ALL[@]}"
      mvn -q -T 1C -pl "$SHARED_MODULES" install -DskipTests || { echo "共享模块 INSTALL 失败"; exit 1; }
      stale=("${ALL[@]}")
    else
      stop_many "${stale[@]}"
    fi
    sleep 2   # 等 Windows 释放 jar 文件锁
    local plist; plist=$(printf 'services/%s-service,' "${stale[@]}"); plist=${plist%,}
    echo "并行构建: $plist"
    mvn -q -T 1C -pl "$plist" clean package -DskipTests || { echo "BUILD FAILED"; exit 1; }
    for svc in "${stale[@]}"; do start_svc "$svc"; done
  else
    echo "无源码变更,跳过构建"
  fi

  # 部署终态:未变更但未运行的服务也拉起
  local down=()
  for svc in "${ALL[@]}"; do
    case " ${stale[*]-} " in *" $svc "*) continue ;; esac
    [ -z "$(pid_on_port "$(port_of "$svc")")" ] && down+=("$svc")
  done
  if [ ${#down[@]} -gt 0 ]; then
    echo "补充启动未运行服务: ${down[*]}"
    for svc in "${down[@]}"; do start_svc "$svc"; done
  fi

  if [ ${#stale[@]} -eq 0 ] && [ ${#down[@]} -eq 0 ]; then
    echo "全部服务已在运行:"
    cmd_status
  else
    wait_health "${stale[@]}" "${down[@]}" || exit 1
    echo "部署完成。"
  fi
}

case "${1:-}" in
  start)   cmd_start ;;
  stop)    cmd_stop ;;
  restart) if [ -n "${2:-}" ]; then cmd_restart_one "$2"; else cmd_stop; sleep 3; cmd_start; fi ;;
  status)  cmd_status ;;
  build)   [ -n "${2:-}" ] && cmd_build "$2" || { echo "usage: ./nora.sh build <svc|common>"; exit 1; } ;;
  deploy)  cmd_deploy "${2:-auto}" ;;
  *) echo "usage: ./nora.sh <start|stop|restart|status|build|deploy> [服务名|all]"; exit 1 ;;
esac
