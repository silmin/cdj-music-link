#!/usr/bin/env bash
# Start the server alone against a real Pro DJ Link network (no emulator).
#
#   scripts/live.sh [up|status|logs [compose logs args]|down]     (default: up; logs defaults to -f)
#
# Mode (LIVE_MODE=compose|jvm, auto-detected when unset):
#   compose  Linux with docker (the Pi): deploy/compose.yaml, host network, GHCR image, detached.
#   jvm      anything else (a Mac on the venue LAN): builds server with Gradle and runs it in the
#            foreground. Docker Desktop's bridge does not pass Pro DJ Link broadcasts, so on macOS
#            the JVM has to run on the host.
# Settings come from deploy/.env (created from .env.example when missing).
#   LIVE_WAIT     seconds to wait for online:true after the HTTP API answers (default 30)
#   LIVE_NO_PULL  =1 to skip `docker compose pull` (compose mode, offline venue)
set -euo pipefail
cd "$(dirname "$0")/.."

CMD=${1:-up}
ENV_FILE=deploy/.env
[[ -f $ENV_FILE ]] || { cp deploy/.env.example "$ENV_FILE"; echo "created $ENV_FILE from .env.example"; }

# read PORT from .env without exporting everything yet
PORT=$(sed -nE 's/^PORT=([0-9]+).*/\1/p' "$ENV_FILE" | tail -1)
PORT=${PORT:-17080}
BASE=http://localhost:$PORT
WAIT=${LIVE_WAIT:-30}

if [[ -z "${LIVE_MODE:-}" ]]; then
  if [[ "$(uname -s)" == Linux ]] && command -v docker >/dev/null 2>&1; then LIVE_MODE=compose; else LIVE_MODE=jvm; fi
fi
COMPOSE=(docker compose -f deploy/compose.yaml)

field() { sed -nE "s/.*\"$1\":([^,}]*).*/\1/p" | head -1; }

health() { curl -fsS --max-time 2 "$BASE/v1/health" 2>/dev/null; }

# Wait until the HTTP API answers, then until online:true (bounded). Prints a summary; never fails.
wait_and_report() {
  local i body
  for ((i = 0; i < 90; i++)); do
    body=$(health) && break
    sleep 1
  done
  if [[ -z "${body:-}" ]]; then
    echo "server did not answer on $BASE within 90s" >&2
    return 0
  fi
  echo "api: $BASE (answered after ${i}s)"
  for ((i = 0; i < WAIT; i++)); do
    [[ "$(echo "$body" | field online)" == true ]] && break
    sleep 1
    body=$(health) || body=""
  done
  report "$body"
}

report() {
  local body=$1
  if [[ "$(echo "$body" | field online)" == true ]]; then
    echo "online: true  deviceNumber=$(echo "$body" | field deviceNumber)  deviceCount=$(echo "$body" | field deviceCount)"
    echo "devices:"
    { curl -fsS --max-time 2 "$BASE/v1/devices"; echo; } | tr '{' '\n' | sed -nE 's/.*"number":([0-9]+),"name":"([^"]*)","address":"([^"]*)".*/  \1  \2  \3/p'
  else
    echo "online: false  (no CDJ seen yet: VirtualCdj needs another Pro DJ Link device on the wire;"
    echo "               check eth0 is on the DJ switch and \`tcpdump -n udp port 50000\` shows keep-alives)"
  fi
  echo "tui: cdj-tui --base $BASE"
}

case "$LIVE_MODE:$CMD" in
  compose:up)
    if [[ "${LIVE_NO_PULL:-}" != 1 ]]; then
      "${COMPOSE[@]}" pull -q || echo "pull failed (offline?); using the local image" >&2
    fi
    "${COMPOSE[@]}" up -d
    wait_and_report
    echo "logs: scripts/live.sh logs    stop: scripts/live.sh down"
    ;;
  compose:down)   "${COMPOSE[@]}" down ;;
  compose:logs)   shift; [[ $# -gt 0 ]] || set -- -f; exec "${COMPOSE[@]}" logs "$@" ;;
  jvm:up)
    echo "building server (gradle)..."
    ./gradlew -q :server:installDist
    set -a; # shellcheck disable=SC1090
    source "$ENV_FILE"; set +a
    server/build/install/server/bin/server &
    PID=$!
    trap 'kill "$PID" 2>/dev/null; wait "$PID" 2>/dev/null || true' INT TERM EXIT
    wait_and_report
    echo "running in the foreground (pid $PID); Ctrl-C to stop"
    wait "$PID"
    ;;
  jvm:down)       pkill -f 'server/build/install/server' && echo "stopped" || echo "not running" ;;
  jvm:logs)       echo "jvm mode logs to the terminal that ran 'live.sh up'" ;;
  *:status)
    body=$(health) || { echo "server not answering on $BASE"; exit 1; }
    report "$body"
    ;;
  *) echo "usage: $0 [up|status|logs|down]   (LIVE_MODE=compose|jvm)" >&2; exit 2 ;;
esac
