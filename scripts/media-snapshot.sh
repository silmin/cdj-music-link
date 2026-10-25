#!/usr/bin/env bash
# rekordbox で書き出した USB から、模擬環境(deploy/compose.emu.yaml の media プロファイル)に
# 必要な分だけをローカルに複製する。USB を挿さずに emu-watch / emu-test を回せるようになる。
#
#   scripts/media-snapshot.sh /Volumes/<USB名> ~/src/decodefrens/rekordbox-usb
#
# 複製するもの: .PIONEER(または PIONEER)配下すべて(export.pdb、USBANLZ の解析ファイル、Artwork、設定)。
# 音源 Contents/ は同じディレクトリ構成で 0 バイトのダミーを作るだけ(server は音源を読まない。
# prolink serve はパスの存在だけ見る)。実機や rekordbox でこの複製を使ってはいけない。
set -euo pipefail

src=${1:?usage: media-snapshot.sh <usb-root> <dest-dir>}
dst=${2:?usage: media-snapshot.sh <usb-root> <dest-dir>}

pioneer=""
for cand in .PIONEER PIONEER; do
  [ -f "$src/$cand/rekordbox/export.pdb" ] && pioneer=$cand && break
done
[ -n "$pioneer" ] || { echo "no $src/.PIONEER/rekordbox/export.pdb (or PIONEER/...) found" >&2; exit 1; }
[ -d "$src/Contents" ] || { echo "no $src/Contents directory" >&2; exit 1; }

mkdir -p "$dst"
echo "copying $src/$pioneer -> $dst/.PIONEER"
rsync -a "$src/$pioneer/" "$dst/.PIONEER/"

echo "recreating $src/Contents as empty placeholder files under $dst/Contents"
( cd "$src" && find Contents -type d -print0 ) | ( cd "$dst" && xargs -0 mkdir -p )
( cd "$src" && find Contents -type f -print0 ) | ( cd "$dst" && xargs -0 -I{} sh -c ': > "$1"' _ {} )

files=$(find "$dst/Contents" -type f | wc -l | tr -d ' ')
echo "done: $(du -sh "$dst" | cut -f1) total, $files placeholder audio files"
echo
echo "deploy/media/.env に次を設定:"
echo "  MEDIA_ROOT=$dst"
