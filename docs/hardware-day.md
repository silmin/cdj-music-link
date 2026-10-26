# 実機検証の日の手順(1 枚)

機材: CDJ-2000NXS2 ×2、Xone:92(Pro DJ Link 非対応)、DJ スイッチ、Pi、ノート PC。
詳細な確認項目と記入欄は `docs/hardware-checklist.md`。この紙は当日の流れだけ。

## 前日まで

- [ ] Pi: `deploy/pi-setup.md` の 1〜5 を済ませ、`scripts/live.sh` を一度通して(pull 済みにして)おく。会場では `LIVE_NO_PULL=1 scripts/live.sh`
- [ ] Pi: `deploy/.env` は `DEVICE_NUMBER=` 空(自動)。eth0 は DHCP → link-local フォールバック、wlan0 は API 用
- [ ] PC: [Releases](https://github.com/silmin/cdj-music-link/releases) から自分の OS の `cdj-tui` を落として展開し、`./cdj-tui --version` が通る(macOS で止められたら `xattr -d com.apple.quarantine cdj-tui`)。`curl`、`tcpdump` が使える
- [ ] Pi: 同じ Release の `linux_arm64` を Pi で落として展開しておく(`curl -LO`、Go は不要。64bit OS 前提)。`./cdj-tui --version` が通る
- [ ] 持ち物: rekordbox 書き出し済み USB(解析済み曲入り)、CD か未解析曲(dbserver 経路用)、LAN ケーブル ×3、USB ケーブル(PC DJ 実験用)

## 配線

1. CDJ ×2 と Pi の eth0 を同じ DJ スイッチに挿す(Xone:92 は LAN なし。音声は CDJ → Xone:92 のアナログのみ)
2. Pi の電源を入れ、PC を Pi の wlan0 側(会場 Wi-Fi)から `http://cdj-music-link.local:17080/v1/health` で叩けることを確認
3. PC で `cdj-tui --base http://cdj-music-link.local:17080` を開いておく(以降はこれを見ながら進める)

## 手順(上から順に。各項目の結果を checklist に書く)

1. **パケット** Pi で `sudo tcpdump -i eth0 -n udp portrange 50000-50002`。CDJ 2 台分の 50000(keep-alive)、50002(status)が見えること。再生すると 50001(beat)
2. **起動** Pi で `LIVE_NO_PULL=1 scripts/live.sh`(起動済みなら `scripts/live.sh status`)。`online: true`、`deviceNumber` が 1〜4、devices に CDJ が 2 台出ること
3. **USB メタデータ** CDJ1 に USB の解析済み曲をロード → `/v1/players/1/track` の `title` が埋まるまでの秒数(NFS 経路)
4. **dbserver 経路** CD か未解析曲をロード → `title` が埋まるまでの秒数。`deviceNumber` が 5 以上ならこの項目は null のままが正しい
5. **解析データ** `scripts/smoke.sh` を `BASE=http://cdj-music-link.local:17080` で実行。`artwork`、`waveform/preview`、`waveform/detail`、`beatgrid` が 200。detail の `segments` がカラーなら 1200、ブルーなら 450 近辺
6. **再生状態** 2 台とも再生。`/v1/players` で両方 `isPlaying: true`。位置が CDJ の表示と大きくずれないか。SSE `curl -N /v1/stream` の `beat` 間隔が BPM と合う
7. **now-playing(Xone:92 想定)** `isOnAir` が両方 null であること。2 台再生中に MASTER を切り替え → `/v1/now-playing` がマスター側に追従。両方 SYNC で MASTER なし → 番号の小さい方
8. **切り替え競合** CDJ2 に CDJ1 と同じ曲をロード、続けて別曲へ素早く切り替え。`track` イベントが `null` を挟まず、`title` が数秒で埋まる
9. **切断と復帰** CDJ1 の電源を落とす → `device` イベント `disconnected`、`online: true` のまま。入れ直して `connected`。次に Pi の LAN を抜いて `online: false`、挿し直して `online` イベントが 1 回
10. **PC DJ 実験(時間があれば)** rekordbox の PC を LAN に参加させ、CDJ1 を USB 直結のコントロールモードにする。`/v1/devices` に rekordbox(番号 17 付近)が出るか、`/v1/players/1` に `track`(`slot: collection`)が出るか、`title` と拍が追従するか。出なければ Link Export か PC 側エージェントに切り替える判断材料として記録

## 記録して帰るもの

- `docs/hardware-checklist.md` の記入(秒数、`deviceNumber`、気づき)
- Pi のログ: `scripts/live.sh logs --no-color > hw-$(date +%F).log`
- 可能なら `sudo tcpdump -i eth0 -w hw-$(date +%F).pcap udp portrange 50000-50002` を手順 6 と 10 の間だけ回す(fake-cdj のテンプレートを自前キャプチャに差し替える材料)
