# 実機チェックリスト(CDJ-2000NXS2)

イベント前に実機で確認する項目。模擬環境(`scripts/emu-test.sh`)は拍・ステータス・接続/切断まで。`EMU_MEDIA=1` では模擬 USB(prolink serve)を足し、dbserver 経由のメタデータ経路も通す。on-air と NFS 経路はここで詰める。

「(模擬で確認済み)」の項目は、模擬環境で同じ経路が動くことを確かめたもの。実機での確認は別に要る。

メモ欄: 日付 / 機材 / Pi の `deviceNumber` / 備考

## ネットワーク

- [ ] Pi を DJ スイッチ(または DJM の汎用 LAN ポート)に接続した(DJM のチャンネル専用ポートではない)
- [ ] `sudo tcpdump -i eth0 -n udp portrange 50000-50002` で CDJ の keep-alive(50000)とステータス(50002)、拍(50001)が見える
- [ ] `curl localhost:17080/v1/health` が `online: true` で、`deviceNumber` が想定どおり(1〜4 の空き番号)
- [ ] 値: `deviceNumber` = ____

## メタデータ

- [ ] USB の解析済み曲をロード → `/v1/players/{n}/track` の `title` が埋まるまで ____ 秒(NFS 経路)
- [ ] CD または未解析曲をロード → `title` が埋まるまで ____ 秒(dbserver 経路。`deviceNumber` が 4 以下のときのみ)(模擬で確認済み: device 4 の dbserver から解析済み曲の `title` が数秒で埋まる)
- [ ] 曲を切り替える / 同じ曲を別デッキにロードする → `track.title` が ____ 秒以内に埋まり、ロード中に `track: null` の `track` イベントが出ない
- [ ] `/v1/players/{n}/artwork`、`/waveform/preview`、`/waveform/detail`、`/beatgrid` がデータを返す(模擬で確認済み: preview と beatgrid。prolink がカラー波形に応答しないため preview はブルー)

## 再生状態

- [ ] 2 台同時再生で、両方の `isPlaying` が true
- [ ] 再生位置(補間値)が実機の表示と大きくずれない
- [ ] SSE `/v1/stream` の `beat` 間隔が BPM と合う(128 BPM で約 468 ms)

## ミキサー

- [ ] ミキサー接続時、`isOnAir` が null でなく、フェーダー操作で切り替わる
- [ ] ミキサーなしの構成で `isOnAir` が null、`/v1/now-playing` が再生中のプレイヤーを返す

## 切断と復帰

- [ ] CDJ の電源を落とす → `device` イベント(`disconnected`)が来て、server は `online: true` のまま。入れ直すと `connected` が来る
- [ ] DJ Link から全機器を外す → `online: false`。繋ぎ直すと `online: true` の `online` イベントが 1 回だけ来る
- [ ] rekordbox 入りノートPCを LAN に出し入れしても、`/v1/devices` が追従し、番号の衝突が起きない

## PC DJ(rekordbox HID / コントロールモード)の確認

PC DJ は rekordbox PERFORMANCE モードで CDJ を HID コントローラー兼オーディオインターフェースとして使う想定。Pro DJ Link 上に何が流れるかは一次情報で確認できていないので、実機で見る。

- [ ] rekordbox の PC も LAN に参加させ、CDJ をコントロールモード(USB 直結、または DJM-900NXS2 経由の CONTROL MODE (DJM-USB))にする
- [ ] `/v1/devices` に `rekordbox`(番号 17 付近、kind collection)が出るか
- [ ] rekordbox のデッキで曲を再生したとき、その CDJ の `/v1/players/N` に `track` が出るか(`slot` が `collection`、`sourcePlayer` が rekordbox の番号になる想定)
- [ ] `track.title` が埋まるか(rekordbox の dbserver 経路。Pi の番号が 1〜4 であること)
- [ ] `isPlaying`、`tempo`、拍イベント、`position` が追従するか
- [ ] 何も出ない場合は代替を決める: Link Export(CDJ 自身で PC のライブラリを再生)か、PC 側エージェント(rekordbox の履歴、遅延あり・拍なし)
