#!/usr/bin/env bash
# Long-running emulation: brings the compose stack up, prints a human-readable
# SSE event log, and (by default) plays a DJ-style deck-switching scenario.
# Usage: scripts/emu-watch.sh [--beats] [--no-scenario] [--keep]   (Ctrl-C to stop)
set -uo pipefail
cd "$(dirname "$0")/.."

BEATS=0 SCENARIO=1 KEEP=0
for a in "$@"; do
  case $a in
    --beats) BEATS=1 ;;
    --no-scenario) SCENARIO=0 ;;
    --keep) KEEP=1 ;;
    -h|--help) sed -n 2,4p "$0"; exit 0 ;;
    *) echo "unknown flag: $a" >&2; exit 2 ;;
  esac
done

BASE=${BASE:-http://localhost:17080}
EMU_MEDIA=${EMU_MEDIA:-}
if [[ -z "$EMU_MEDIA" ]]; then
  [[ -f deploy/media/.env ]] && EMU_MEDIA=1 || EMU_MEDIA=0
fi
MEDIA_DEVICE=${MEDIA_DEVICE:-4}
EMU_SWITCH_SEC=${EMU_SWITCH_SEC:-45}
EMU_OVERLAP_SEC=${EMU_OVERLAP_SEC:-12}
export MEDIA_DEVICE

COMPOSE=(docker compose -f deploy/compose.emu.yaml)
[[ "$EMU_MEDIA" == 1 ]] && COMPOSE+=(--profile media --env-file deploy/media/.env)

PIDS=()
ts() { date +%H:%M:%S; }
say() { printf '%s  %s\n' "$(ts)" "$*"; }

compose_up() {
  say "compose up (media=$EMU_MEDIA)"
  "${COMPOSE[@]}" up -d --build >/dev/null 2>&1 || {
    echo "compose up failed" >&2; "${COMPOSE[@]}" logs --tail=40 2>&1; exit 1; }
}

wait_online() {
  local i
  for ((i = 0; i < 90; i++)); do
    [[ "$(curl -sf "$BASE/v1/health" 2>/dev/null | jq -r .online 2>/dev/null)" == "true" ]] && return 0
    sleep 1
  done
  echo "server not online after 90s" >&2
  "${COMPOSE[@]}" logs --tail=40 server 2>&1
  "${COMPOSE[@]}" down >/dev/null 2>&1
  exit 1
}

kill_tree() { pkill -P "$1" 2>/dev/null; kill "$1" 2>/dev/null; }
cleanup() {
  trap '' INT TERM
  echo
  say "stopping"
  local p
  for p in ${PIDS[@]+"${PIDS[@]}"}; do kill_tree "$p"; done
  wait 2>/dev/null
  [[ "$KEEP" == 1 ]] || "${COMPOSE[@]}" down >/dev/null 2>&1
  exit 0
}

# --- event formatting -------------------------------------------------------

# Per-player "last printed" cache in variables (bash 3.2 has no assoc arrays).
cache_get() { local v="c_$1_$2"; printf '%s' "${!v:-}"; }
cache_set() { printf -v "c_$1_$2" '%s' "$3"; }

fmt_player() {  # $1 = player JSON; prints only when state changed
  local n key line
  n=$(jq -r .number <<<"$1")
  key=$(jq -r '[.playState,.isPlaying,.isTempoMaster,.isOnAir]|join(",")' <<<"$1")
  [[ "$(cache_get "$n" st)" == "$key" ]] && return
  cache_set "$n" st "$key"
  line=$(jq -r '"player \(.number)  \(.playState)"
    + (if .isPlaying then "  \(.tempo|.*10|round/10|tostring|if test("\\.") then . else .+".0" end) BPM" else "" end)
    + (if .isTempoMaster then "  master" else "" end)
    + (if .isOnAir then "  on-air" else "" end)' <<<"$1")
  say "$line"
}

fmt_track() {  # $1 = SseTrack JSON
  local n line
  n=$(jq -r .player <<<"$1")
  line=$(jq -r '"player \(.player)  " + (if .track == null then "track unloaded"
    elif .track.title == null then "track loaded  id=\(.track.id) from device \(.track.sourcePlayer) (\(.track.slot), \(.track.type))"
    else "metadata  \"\(.track.title)\" — \(.track.artist // "(no artist)")"
      + (if .track.key then "  key \(.track.key)" else "" end)
      + (if .track.startingBpm then "  \(.track.startingBpm|round) BPM" else "" end)
      + (if .track.durationSec then "  \(.track.durationSec/60|floor):\(.track.durationSec%60|tostring|if length<2 then "0"+. else . end)" else "" end)
    end)' <<<"$1")
  [[ "$(cache_get "$n" tr)" == "$line" ]] && return
  cache_set "$n" tr "$line"
  say "$line"
}

fmt_snapshot() {
  say "snapshot"
  jq -r '"    online: \(.online)",
    "    devices: " + (if (.devices|length)==0 then "(none)" else (.devices|map("\(.number) \(.name) \(.address)")|join(", ")) end),
    (.players[] | "    player \(.number): \(.playState)  track: \(.track.title // (if .track then "(id \(.track.id), no metadata)" else "(none)" end))")' <<<"$1"
  # seed the change-detection caches so the first live events are not duplicated
  local p
  while IFS= read -r p; do fmt_player_seed "$p"; done < <(jq -c '.players[]' <<<"$1")
}
fmt_player_seed() {
  local n; n=$(jq -r .number <<<"$1")
  cache_set "$n" st "$(jq -r '[.playState,.isPlaying,.isTempoMaster,.isOnAir]|join(",")' <<<"$1")"
}

dispatch() {  # $1 = event name, $2 = data
  local ev=$1 d=$2 line
  case $ev in
    online)   [[ "$(jq -r .online <<<"$d")" == true ]] && say "link online" || say "link offline" ;;
    device)   say "$(jq -r '"device \(.device.number) \(.action)  \(.device.name) \(.device.address)"' <<<"$d")" ;;
    player)   fmt_player "$d" ;;
    track)    fmt_track "$d" ;;
    master)   say "$(jq -r 'if .player then "tempo master → player \(.player) (\(.tempo|.*10|round/10|tostring|if test("\\.") then . else .+".0" end) BPM)" else "tempo master → none" end' <<<"$d")" ;;
    beat)     [[ "$BEATS" == 1 ]] || return
              line=$(jq -r 'select(.beatWithinBar == 1 and .beatNumber != null) | ((.position.ms // 0)/100|round) as $t
                | "player \(.player)  bar  beat \(.beatNumber)  \(.tempo|.*10|round/10|tostring|if test("\\.") then . else .+".0" end) BPM  pos \($t/10|floor).\($t%10)s"' <<<"$d")
              [[ -n "$line" ]] && say "$line" ;;
    snapshot) fmt_snapshot "$d" ;;
  esac
}

stream_events() {
  local line ev="" data=""
  while :; do
    while IFS= read -r line; do
      line=${line%$'\r'}
      case $line in
        "")        [[ -n "$ev" && -n "$data" ]] && dispatch "$ev" "$data"; ev="" data="" ;;
        event:*)   ev=${line#event:}; ev=${ev# } ;;
        data:*)    line=${line#data:}; data+="${data:+$'\n'}${line# }" ;;
        *)         ;;  # id:, retry:, comments
      esac
    done < <(curl -sN "$BASE/v1/stream" 2>/dev/null)
    say "stream dropped, reconnecting in 2s"
    ev="" data=""
    sleep 2
  done
}

# --- DJ scenario ------------------------------------------------------------

track_ids() {
  local ids=""
  if [[ "$EMU_MEDIA" == 1 ]]; then
    ids=$(docker run --rm -v "${MEDIA_ROOT:-/Volumes/nordblue2}/.PIONEER:/media/PIONEER:ro" \
      cdj-music-link-media:dev tracks /media/PIONEER/rekordbox/export.pdb 2>/dev/null \
      | awk '$1 ~ /^[0-9]+$/ && $2 ~ /^[0-9]+:[0-9]+$/ {print $1}')
    [[ -z "$ids" ]] && ids=${MEDIA_TRACK_ID:-308}
  else
    ids=$(seq 101 110)
  fi
  printf '%s\n' "$ids"
}

deck_post() {  # $1 deck (1|2), $2 path, $3 optional JSON body
  local port=$((17090 + $1))
  curl -sf --max-time 5 -X POST "http://localhost:$port$2" -H 'Content-Type: application/json' \
    ${3:+-d "$3"} >/dev/null || say "scenario: POST deck $1 $2 failed"
}

scenario() {
  local ids=() id i=0 target=2 other=1 body
  while IFS= read -r id; do ids+=("$id"); done < <(track_ids)
  say "scenario: ${#ids[@]} track id(s), switch every ${EMU_SWITCH_SEC}s, overlap ${EMU_OVERLAP_SEC}s"
  while :; do
    id=${ids[i % ${#ids[@]}]}; i=$((i + 1))
    body="{\"trackId\":$id,\"slot\":3,\"trackType\":1"
    [[ "$EMU_MEDIA" == 1 ]] && body+=",\"sourcePlayer\":$MEDIA_DEVICE"
    body+="}"
    say "scenario: load id=$id on deck $target, then play"
    deck_post "$target" /load "$body"
    sleep 1
    deck_post "$target" /play
    sleep "$EMU_OVERLAP_SEC"
    say "scenario: pause deck $other"
    deck_post "$other" /pause
    sleep "$((EMU_SWITCH_SEC > EMU_OVERLAP_SEC ? EMU_SWITCH_SEC - EMU_OVERLAP_SEC : 1))"
    # the deck that just took over is now live; the paused one is idle
    local t=$target; target=$other; other=$t
  done
}

# --- main -------------------------------------------------------------------

command -v jq >/dev/null && command -v curl >/dev/null || { echo "needs jq and curl" >&2; exit 1; }
trap cleanup INT TERM

compose_up
wait_online
say "server online; streaming events (Ctrl-C to stop)"
stream_events & PIDS+=($!); disown
if [[ "$SCENARIO" == 1 ]]; then
  sleep 3  # let the snapshot print first
  scenario & PIDS+=($!); disown
fi
# wait returns on a trapped signal; the loop keeps us alive if a job exits.
while :; do wait "${PIDS[0]}"; sleep 1; done
