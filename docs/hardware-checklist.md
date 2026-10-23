# 実機チェックリスト(CDJ-2000NXS2)

イベント前に実機で確認する項目。模擬環境(`scripts/emu-test.sh`)は拍・ステータス・接続/切断まで。メタデータ経路と on-air はここで詰める。

メモ欄: 日付 / 機材 / Pi の `deviceNumber` / 備考

## ネットワーク

- [ ] Pi を DJ スイッチ(または DJM の汎用 LAN ポート)に接続した(DJM のチャンネル専用ポートではない)
- [ ] `sudo tcpdump -i eth0 -n udp portrange 50000-50002` で CDJ の keep-alive(50000)とステータス(50002)、拍(50001)が見える
- [ ] `curl localhost:17080/v1/health` が `online: true` で、`deviceNumber` が想定どおり(1〜4 の空き番号)
- [ ] 値: `deviceNumber` = ____

## メタデータ

- [ ] USB の解析済み曲をロード → `/v1/players/{n}/track` の `title` が埋まるまで ____ 秒(NFS 経路)
- [ ] CD または未解析曲をロード → `title` が埋まるまで ____ 秒(dbserver 経路。`deviceNumber` が 4 以下のときのみ)
- [ ] 曲を切り替える / 同じ曲を別デッキにロードする → `track.title` が ____ 秒以内に埋まり、ロード中に `track: null` の `track` イベントが出ない
- [ ] `/v1/players/{n}/artwork`、`/waveform/preview`、`/waveform/detail`、`/beatgrid` がデータを返す

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
