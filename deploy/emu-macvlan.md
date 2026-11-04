# 模擬 CDJ を実 LAN に出す(rekordbox LINK EXPORT の検証)

`deploy/compose.emu.yaml` は Docker の bridge の中だけで完結する。rekordbox の入ったノート PC から模擬 CDJ を
見せたいときは、Linux ホスト(Pi)で macvlan を使い、fake-cdj ×2 と server にホストの Ethernet 上のアドレスを
持たせる。Mac の Docker Desktop では VM の中なので使えない。

## 前提

- Linux + Docker のホスト(Pi)。`eth0` が rekordbox の PC と同じ L2(同じルーター/スイッチ)にいる
- その LAN で空いている IP が 3 つ(既定 192.168.33.240〜242、LAN は 192.168.32.0/23)
- rekordbox は EXPORT モード。PC は同じ LAN(Wi-Fi でもよい)

## 起動

```sh
cd ~/cdj-music-link
# 必要なら値を変える: MACVLAN_PARENT MACVLAN_SUBNET MACVLAN_GATEWAY FAKE1_IP FAKE2_IP SERVER_IP FAKE_NAME
docker compose -f deploy/compose.emu.macvlan.yaml pull
docker compose -f deploy/compose.emu.macvlan.yaml up -d
curl localhost:17080/v1/health      # online: true, devices に模擬 CDJ 2 台
```

fake の機種名は既定で `CDJ-2000NXS2`(rekordbox が機種名を見ている可能性に備えて)。`FAKE_NAME` で変えられる。
fake は曲を持たずに待機する(`FAKE_AUTOPLAY=false`、`FAKE_TRACK_ID=0`)。

## rekordbox から曲を載せる

1. rekordbox(EXPORT モード)の画面下の LINK ステータスパネルに、模擬 CDJ 2 台が出ることを確認する
2. ブラウザから曲をプレイヤーのアイコンへドラッグする。rekordbox は 50002 宛に load track コマンド(type 0x19)を送り、
   fake-cdj がそれを受けてデッキに `slot=collection(4)`、`sourcePlayer=<rekordbox の番号>`、rekordbox の曲 ID を載せる
3. Pi で `cdj-tui`(localhost:17080)を見る。曲名は server が rekordbox の dbserver から取る

fake-cdj は曲を「再生」できる(`/play` の制御 API は macvlan 側には公開していないので、必要なら `docker exec` で
`curl -X POST localhost:17090/play` を叩く)。

## 注意

- macvlan のコンテナにはホスト自身から届かない。API は bridge 側のポート公開(`localhost:17080`)で見る
- rekordbox が自作の模擬 CDJ をプレイヤーとして受け入れるか、ロード後にメタデータを返すかは未検証の項目
- 片付けは `docker compose -f deploy/compose.emu.macvlan.yaml down`
