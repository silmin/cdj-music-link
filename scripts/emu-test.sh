#!/usr/bin/env bash
# Integration test against the emulated CDJ compose stack.
set -euo pipefail
cd "$(dirname "$0")/.."

COMPOSE=(docker compose -f deploy/compose.emu.yaml)
BASE=http://localhost:17080

# EMU_MEDIA=1: also run the emulated rekordbox USB (deploy/media) and check metadata.
EMU_MEDIA=${EMU_MEDIA:-}
if [[ "$EMU_MEDIA" == "1" ]]; then
  export MEDIA_DEVICE=${MEDIA_DEVICE:-4}
  if [[ -z "${MEDIA_TRACK_ID:-}" ]]; then
    echo "FAIL: EMU_MEDIA=1 needs MEDIA_TRACK_ID (a rekordbox id on the USB, see deploy/media/README.md)" >&2
    exit 1
  fi
  export MEDIA_TRACK_ID
  COMPOSE+=(--profile media)
fi

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
# The media device (number MEDIA_DEVICE) is listed as a player too.
expected_players=2 players_label=two
[[ "$EMU_MEDIA" == "1" ]] && expected_players=3 players_label=three
players_listed() { [[ "$(curl -sf "$BASE/v1/players" | jq length)" == "$expected_players" ]]; }

"${COMPOSE[@]}" up -d --build || fail "compose up"
ok "compose up"

wait_for "server online (<=90s)" 90 is_online
wait_for "$players_label players listed" 30 players_listed

p1=$(curl -sf "$BASE/v1/players/1") || fail "GET players/1"
[[ "$(jq -r .playState <<<"$p1")" == "playing" ]] || fail "player 1 playState is playing"
jq -e '.tempo >= 127.9 and .tempo <= 128.1' <<<"$p1" >/dev/null || fail "player 1 tempo within 127.9-128.1"
ok "player 1 playing at ~128 BPM"

expected_id=101
[[ "$EMU_MEDIA" == "1" ]] && expected_id=$MEDIA_TRACK_ID
[[ "$(jq -r .track.id <<<"$p1")" == "$expected_id" ]] || fail "player 1 track.id is $expected_id"
ok "player 1 track id $expected_id"

beats=$(curl -sN --max-time 3 "$BASE/v1/stream" | grep -c '^event: beat' || true)
[[ "$beats" -ge 4 ]] || fail "SSE beat events >= 4 (got $beats)"
ok "SSE beat events ($beats)"

curl -sf -X POST http://localhost:17092/play >/dev/null || fail "POST fake-cdj-2 /play"
is_playing_2() { [[ "$(curl -sf "$BASE/v1/players/2" | jq -r .isPlaying)" == "true" ]]; }
wait_for "player 2 isPlaying after /play" 10 is_playing_2

if [[ "$EMU_MEDIA" == "1" ]]; then
  has_title()    { [[ "$(curl -sf "$BASE/v1/players/1" | jq -r '.track.title // empty')" != "" ]]; }
  # prolink 0.5.0 does not answer beat-link's analysis-tag request (colour PWV4, 1200 segments),
  # so beat-link falls back to the blue preview. Accept any non-empty preview.
  has_preview()  { curl -sf "$BASE/v1/players/1/waveform/preview" | jq -e '.segments > 0' >/dev/null; }
  has_beatgrid() { curl -sf "$BASE/v1/players/1/beatgrid" | jq -e '.beats | length > 0' >/dev/null; }
  position_ms()  { curl -sf "$BASE/v1/players/1" | jq -r '.position.ms // empty'; }

  wait_for "media: player 1 track.title (<=60s)" 60 has_title
  echo "   title: $(curl -sf "$BASE/v1/players/1" | jq -c '.track | {id, title, artist}')"
  wait_for "media: waveform preview 200, segments > 0" 30 has_preview
  echo "   preview: $(curl -sf "$BASE/v1/players/1/waveform/preview" | jq -c '{style, segments}')"
  wait_for "media: beatgrid 200, beats non-empty" 30 has_beatgrid

  pos1=$(position_ms) || fail "media: GET players/1 for position (first)"
  sleep 2
  pos2=$(position_ms) || fail "media: GET players/1 for position (second)"
  [[ "$pos1" =~ ^[0-9]+$ && "$pos2" =~ ^[0-9]+$ ]] || fail "media: position.ms is an integer (got '$pos1' -> '$pos2')"
  (( pos2 > pos1 )) || fail "media: position.ms increases (got '$pos1' -> '$pos2')"
  ok "media: position.ms increases ($pos1 -> $pos2)"

  curl -sf -X POST http://localhost:17091/load -H 'Content-Type: application/json' \
    -d "{\"trackId\":999999,\"slot\":3,\"trackType\":1,\"sourcePlayer\":$MEDIA_DEVICE}" >/dev/null \
    || fail "POST fake-cdj-1 /load unknown id"
  sleep 10
  unknown_title=$(curl -sf "$BASE/v1/players/1" | jq -c .track.title)
  [[ "$unknown_title" == "null" ]] || fail "media: unknown id leaves track.title null (got $unknown_title)"
  is_online || fail "media: server stays online after unknown id"
  ok "media: unknown id 999999 -> title null, still online"
fi

# With media, the media device is a peer too: stop it with the fakes so the server goes offline.
link_peers=(fake-cdj-1 fake-cdj-2)
[[ "$EMU_MEDIA" == "1" ]] && link_peers+=(media)
"${COMPOSE[@]}" stop "${link_peers[@]}" >/dev/null 2>&1 || fail "stop fakes"
wait_for "server offline after fakes stop (<=20s)" 20 is_offline

"${COMPOSE[@]}" start "${link_peers[@]}" >/dev/null 2>&1 || fail "start fakes"
wait_for "server online again (<=90s)" 90 is_online
wait_for "$players_label players listed again" 30 players_listed

echo "ALL OK"
