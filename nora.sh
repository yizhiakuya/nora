#!/usr/bin/env bash
# Nora 后端开发环境一键管理脚本
# 用法: ./nora.sh <start|stop|restart|status|build> [服务名]
#   start            启动全部六服务(基础设施容器需已运行)
#   stop             停全部
#   restart [svc]    重启全部或单个(单个=改代码后的标准流程)
#   status           端口 + 健康一览
#   build svc        停→clean 打包→启动单个服务;build common 先 install nora-common
set -u
ROOT=/d/claude/Nora/nora-api
LOGDIR=/d/claude/Nora
PORTS=(gateway:8080 file:8081 rag:8082 agent:8083 datasource:8084 env:8085)
ALL=(gateway file rag agent datasource env)

port_of() { echo "${PORTS[@]}" | tr ' ' '\n' | grep "^$1:" | cut -d: -f2; }

pid_on_port() { netstat -ano | grep ":$1 " | grep LISTENING | head -1 | awk '{print $NF}'; }

stop_svc() {
  local p=$(port_of "$1"); local pid=$(pid_on_port "$p")
  if [ -n "$pid" ]; then powershell -Command "Stop-Process -Id $pid -Force" 2>/dev/null; echo "stopped $1 (pid $pid, port $p)";
  else echo "$1 not running"; fi
}

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

cmd_status() {
  for entry in "${PORTS[@]}"; do
    svc=${entry%%:*}; p=${entry##*:}
    listen=$(netstat -ano | grep ":$p " | grep -c LISTENING)
    printf "%-10s :%s  listen=%s  health=%s\n" "$svc" "$p" "$listen" "$(health $p)"
  done
}

cmd_stop() {
  for svc in "${ALL[@]}"; do stop_svc "$svc"; done
}

cmd_start() {
  for svc in "${ALL[@]}"; do
    if [ -n "$(pid_on_port $(port_of $svc))" ]; then echo "$svc already running"; else start_svc "$svc"; fi
  done
  echo "waiting 30s for Nacos registration ..."; sleep 30; cmd_status
}

cmd_restart_one() {
  local svc=$1
  stop_svc "$svc"
  cd "$ROOT" || exit 1
  echo "clean package $svc ..."
  mvn -q -pl services/$svc-service clean package -DskipTests || { echo "BUILD FAILED"; exit 1; }
  start_svc "$svc"
  sleep 12
  echo "$svc health: $(health $(port_of $svc))"
}

cmd_build() {
  local target=$1
  cd "$ROOT" || exit 1
  if [ "$target" = "common" ]; then
    for svc in "${ALL[@]}"; do stop_svc "$svc"; done
    mvn -q -pl common/nora-common clean install -DskipTests || { echo "INSTALL FAILED"; exit 1; }
    echo "nora-common installed. Run './nora.sh build <svc>' per changed service, or './nora.sh start'."
    return
  fi
  cmd_restart_one "$target"
}

case "${1:-}" in
  start)   cmd_start ;;
  stop)    cmd_stop ;;
  restart) if [ -n "${2:-}" ]; then cmd_restart_one "$2"; else cmd_stop; sleep 3; cmd_start; fi ;;
  status)  cmd_status ;;
  build)   [ -n "${2:-}" ] && cmd_build "$2" || { echo "usage: ./nora.sh build <svc|common>"; exit 1; } ;;
  *) echo "usage: ./nora.sh <start|stop|restart|status|build> [服务名]"; exit 1 ;;
esac
