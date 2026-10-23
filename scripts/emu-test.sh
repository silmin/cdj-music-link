#!/usr/bin/env bash
# Integration test against the emulated CDJ compose stack.
set -euo pipefail
cd "$(dirname "$0")/.."

COMPOSE=(docker compose -f deploy/compose.emu.yaml)
BASE=http://localhost:17080

cleanup() { "${COMPOSE[@]}" down >/dev/null 2>&1 || true; }
trap cleanup EXIT

ok()   { echo "ok: $1"; }
fail() { echo "FAIL: $1"; "${COMPOSE[@]}" logs --tail=40 2>&1 || true; exit 1; }

# wait_for <desc> <timeout-s> <command...>: poll once a second
wait_for() {
  local desc=$1 timeout=$2; shift 2
  local i
  for ((i = 0; i < timeout; i++)); do
    if "$@" >/dev/null 2>&1; then ok "$desc"; return 0; fi
    sleep 1
  done
  fail "$desc"
}

is_online()  { [[ "$(curl -sf "$BASE/v1/health" | jq -r .online)" == "true" ]]; }
is_offline() { [[ "$(curl -sf "$BASE/v1/health" | jq -r .online)" == "false" ]]; }
two_players() { [[ "$(curl -sf "$BASE/v1/players" | jq length)" == "2" ]]; }

"${COMPOSE[@]}" up -d --build || fail "compose up"
ok "compose up"

wait_for "server online (<=90s)" 90 is_online
wait_for "two players listed" 30 two_players

p1=$(curl -sf "$BASE/v1/players/1") || fail "GET players/1"
[[ "$(jq -r .playState <<<"$p1")" == "playing" ]] || fail "player 1 playState is playing"
jq -e '.tempo >= 127.9 and .tempo <= 128.1' <<<"$p1" >/dev/null || fail "player 1 tempo within 127.9-128.1"
ok "player 1 playing at ~128 BPM"

[[ "$(jq -r .track.id <<<"$p1")" == "101" ]] || fail "player 1 track.id is 101"
ok "player 1 track id 101"

beats=$(curl -sN --max-time 3 "$BASE/v1/stream" | grep -c '^event: beat' || true)
[[ "$beats" -ge 4 ]] || fail "SSE beat events >= 4 (got $beats)"
ok "SSE beat events ($beats)"

curl -sf -X POST http://localhost:17092/play >/dev/null || fail "POST fake-cdj-2 /play"
is_playing_2() { [[ "$(curl -sf "$BASE/v1/players/2" | jq -r .isPlaying)" == "true" ]]; }
wait_for "player 2 isPlaying after /play" 10 is_playing_2

"${COMPOSE[@]}" stop fake-cdj-1 fake-cdj-2 >/dev/null 2>&1 || fail "stop fakes"
wait_for "server offline after fakes stop (<=20s)" 20 is_offline

"${COMPOSE[@]}" start fake-cdj-1 fake-cdj-2 >/dev/null 2>&1 || fail "start fakes"
wait_for "server online again (<=90s)" 90 is_online
wait_for "two players listed again" 30 two_players

echo "ALL OK"
