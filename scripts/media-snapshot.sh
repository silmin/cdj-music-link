#!/usr/bin/env bash
# rekordbox で書き出した USB から、模擬環境(deploy/compose.emu.yaml の media プロファイル)に
# 必要な分をローカルに複製する。USB を挿さずに emu-watch / emu-test を回せるようになる。
#
#   scripts/media-snapshot.sh [--audio N|all] <usb-root> <dest-dir>
#
# - .PIONEER(または PIONEER)配下はすべて複製する(export.pdb、USBANLZ の解析ファイル、Artwork、設定)。
# - 音源 Contents/ は同じディレクトリ構成を作り、--audio で指定した数だけ実ファイルを等間隔に選んで
#   複製し、残りは 0 バイトのダミーにする(既定 20。all で全部、0 で全部ダミー)。
#   server は音源を読まず、prolink serve はパスの存在しか見ないので、API の確認はダミーでも同じ結果になる。
#   実ファイルが要るのは、この media を実機 CDJ に LINK で読ませて再生するときだけ。
# - 複製は模擬専用。実機や rekordbox にこの複製を見せてはいけない。
set -euo pipefail

audio=20
while [ $# -gt 0 ]; do
  case "$1" in
    --audio) audio=${2:?--audio needs N or all}; shift 2 ;;
    -h|--help) sed -n '2,13p' "$0"; exit 0 ;;
    *) break ;;
  esac
done
src=${1:?usage: media-snapshot.sh [--audio N|all] <usb-root> <dest-dir>}
dst=${2:?usage: media-snapshot.sh [--audio N|all] <usb-root> <dest-dir>}

pioneer=""
for cand in .PIONEER PIONEER; do
  [ -f "$src/$cand/rekordbox/export.pdb" ] && pioneer=$cand && break
done
[ -n "$pioneer" ] || { echo "no $src/.PIONEER/rekordbox/export.pdb (or PIONEER/...) found" >&2; exit 1; }
[ -d "$src/Contents" ] || { echo "no $src/Contents directory" >&2; exit 1; }

mkdir -p "$dst"
echo "copying $src/$pioneer -> $dst/.PIONEER"
rsync -a "$src/$pioneer/" "$dst/.PIONEER/"

echo "recreating $src/Contents under $dst/Contents"
( cd "$src" && find Contents -type d -print0 ) | ( cd "$dst" && xargs -0 mkdir -p )

# 音源一覧(ソート済み)。等間隔に N 本を選ぶ。
all=$(cd "$src" && find Contents -type f | LC_ALL=C sort)
total=$(printf '%s\n' "$all" | grep -c . || true)
if [ "$audio" = "all" ]; then
  pick=$all
elif [ "$audio" -gt 0 ] 2>/dev/null && [ "$total" -gt 0 ]; then
  pick=$(printf '%s\n' "$all" | awk -v n="$audio" -v t="$total" 'n>=t{print;next} {i++; if (int((i-1)*n/t)!=int(i*n/t)) print}')
else
  pick=""
fi

real=0; dummy=0
while IFS= read -r f; do
  [ -n "$f" ] || continue
  if printf '%s\n' "$pick" | grep -qxF -- "$f"; then
    rsync -a "$src/$f" "$dst/$f"; real=$((real+1))
  else
    [ -s "$dst/$f" ] && { real=$((real+1)); continue; }   # 以前に実ファイルを置いていれば残す
    : > "$dst/$f"; dummy=$((dummy+1))
  fi
done <<< "$all"

echo "done: $(du -sh "$dst" | cut -f1) total — real audio files: $real, placeholders: $dummy"
echo
echo "deploy/media/.env に次を設定:"
echo "  MEDIA_ROOT=$dst"
