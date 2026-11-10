#!/usr/bin/env bash
# DJ 役: USB(export.pdb)の曲を 2 台の fake-cdj に交互にロード・再生し続ける。
# deploy/compose.public.yaml の scenario サービスが使う。設定は環境変数:
#   DECK1_URL / DECK2_URL  fake-cdj の操作 API(既定 http://fake-cdj-1:17090, http://fake-cdj-2:17090)
#   SERVER_URL             server(既定 http://server:17080)。media が見えるまで待つのに使う
#   SOURCE_PLAYER          曲の読み込み元として報告する device 番号(既定 4 = media)
#   SWITCH_SEC             曲を切り替える間隔(既定 45)
#   OVERLAP_SEC            2 台が同時に鳴る時間(既定 12)
#   SHUFFLE                1 なら周回ごとにシャッフル、0 なら export 順(既定 1)
#   PDB                    export.pdb のパス(既定 /media/PIONEER/rekordbox/export.pdb)
set -uo pipefail

DECK_URLS=("" "${DECK1_URL:-http://fake-cdj-1:17090}" "${DECK2_URL:-http://fake-cdj-2:17090}")
SERVER_URL=${SERVER_URL:-http://server:17080}
SOURCE_PLAYER=${SOURCE_PLAYER:-4}
SWITCH_SEC=${SWITCH_SEC:-45}
OVERLAP_SEC=${OVERLAP_SEC:-12}
SHUFFLE=${SHUFFLE:-1}
PDB=${PDB:-/media/PIONEER/rekordbox/export.pdb}

say() { printf '%s  %s\n' "$(date +%H:%M:%S)" "$*"; }

deck_post() {  # $1 deck (1|2), $2 path, $3 optional JSON body
  curl -sf --max-time 5 -X POST "${DECK_URLS[$1]}$2" -H 'Content-Type: application/json' \
    ${3:+-d "$3"} >/dev/null || say "POST deck $1 $2 failed"
}

# ロードした曲のメタデータ(server が media から取る)を待ち、その BPM をデッキのテンポにする。
# メタデータが来なければ 1 を返す(beat-link が読めない曲がある。deploy/public.md 参照)。
match_tempo() {  # $1 deck (1|2), $2 track id
  local body bpm i
  for i in 1 2 3 4 5; do
    sleep 1
    body=$(curl -sf --max-time 5 "$SERVER_URL/v1/players/$1/track")
    grep -q "\"id\":$2,.*\"title\":\"" <<<"$body" || continue  # まだ前の曲、またはメタデータ未着
    bpm=$(grep -o '"startingBpm":[0-9.]*' <<<"$body" | cut -d: -f2)
    [[ -n "$bpm" ]] && deck_post "$1" /tempo "{\"bpm\":$bpm}"
    return 0
  done
  return 1
}

track_ids() {  # 先頭列が rekordbox の id、2 列目が m:ss の行だけを曲として拾う
  local ids
  ids=$(prolink tracks "$PDB" 2>/dev/null | awk '$1 ~ /^[0-9]+$/ && $2 ~ /^[0-9]+:[0-9]+$/ {print $1}')
  if [[ "$SHUFFLE" == 1 ]]; then shuf <<<"$ids"; else printf '%s\n' "$ids"; fi
}

# media が DJ Link に現れる前にロードすると曲名が付かないので、server から見えるまで待つ。
until curl -sf --max-time 5 "$SERVER_URL/v1/devices" | grep -q "\"number\":$SOURCE_PLAYER[,}]"; do
  say "waiting for device $SOURCE_PLAYER on $SERVER_URL"
  sleep 3
done

target=2 other=1
while :; do
  mapfile -t ids < <(track_ids)
  if [[ ${#ids[@]} -eq 0 || -z "${ids[0]}" ]]; then
    say "no tracks found in $PDB"
    exit 1
  fi
  say "${#ids[@]} track(s), switch every ${SWITCH_SEC}s, overlap ${OVERLAP_SEC}s"
  for id in "${ids[@]}"; do
    say "load id=$id on deck $target, then play"
    deck_post "$target" /load "{\"trackId\":$id,\"slot\":3,\"trackType\":1,\"sourcePlayer\":$SOURCE_PLAYER}"
    if ! match_tempo "$target" "$id"; then
      say "no metadata for id=$id, skipping"
      continue
    fi
    deck_post "$target" /play
    sleep "$OVERLAP_SEC"
    say "pause deck $other"
    deck_post "$other" /pause
    sleep "$((SWITCH_SEC > OVERLAP_SEC ? SWITCH_SEC - OVERLAP_SEC : 1))"
    t=$target; target=$other; other=$t
  done
done
