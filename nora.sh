#!/usr/bin/env bash
# Single backend process. Windows delegates to the native PowerShell launcher.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
ACTION=${1:-status}
if [ "$#" -gt 1 ]; then echo 'usage: ./nora.sh <start|stop|restart|status|build|deploy>'; exit 2; fi
case "${OSTYPE:-}" in
  msys*|cygwin*) exec powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$ROOT/scripts/backend.ps1")" -Action "$ACTION" ;;
esac
JAR="$ROOT/nora-api/target/nora-api-0.1.0-SNAPSHOT.jar"
PID_FILE="$ROOT/nora-api/target/nora.pid"
PORT=${NORA_PORT:-18080}
running() {
  [ -f "$PID_FILE" ] || return 1
  NORA_PID=$(cat "$PID_FILE")
  [[ "$NORA_PID" =~ ^[0-9]+$ ]] || return 1
  kill -0 "$NORA_PID" 2>/dev/null && ps -p "$NORA_PID" -o args= | grep -Fq -- "$JAR"
}
stop() {
  if running; then
    kill "$NORA_PID"
    for ((i=0; i<60; i++)); do running || break; sleep 1; done
    if running; then echo 'Nora did not stop; inspect the process before retrying'; return 1; fi
  fi
  rm -f -- "$PID_FILE"
}
start() {
  if running; then echo 'Nora is already running'; return; fi
  [ -f "$JAR" ] || { echo 'Run ./nora.sh build first'; return 1; }
  mkdir -p "$ROOT/logs"
  export NORA_AGENT_WORKSPACE="${NORA_AGENT_WORKSPACE:-$ROOT/agent-workspace}" LOG_PATH="${LOG_PATH:-$ROOT/logs}"
  (cd "$ROOT/nora-api"; nohup java -jar "$JAR" > "$ROOT/logs/nora.stdout.log" 2> "$ROOT/logs/nora.stderr.log" & echo $! > "$PID_FILE")
  for ((i=0; i<60; i++)); do
    running || { echo 'Nora exited; inspect logs/nora.stderr.log'; return 1; }
    if curl -fsS --max-time 3 --noproxy '*' "http://127.0.0.1:$PORT/actuator/health" >/dev/null 2>&1; then echo "Nora ready on $PORT"; return; fi
    sleep 2
  done
  echo 'Nora did not become healthy within 120 seconds'; return 1
}
case "$ACTION" in
  start) start ;;
  stop) stop ;;
  restart) stop; start ;;
  status) running; curl -fsS --max-time 5 --noproxy '*' "http://127.0.0.1:$PORT/actuator/health" ;;
  build|deploy) stop; (cd "$ROOT/nora-api"; mvn verify); start ;;
  *) echo 'usage: ./nora.sh <start|stop|restart|status|build|deploy>'; exit 2 ;;
esac
