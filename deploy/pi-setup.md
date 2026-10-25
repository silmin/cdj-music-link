# Raspberry Pi セットアップ

対象: Raspberry Pi 4 / 5(4GB 以上)、Raspberry Pi OS 64bit。

## 構成

- `eth0`: DJ 機材だけの閉じた有線ネットワーク(Pro DJ Link)。
- `wlan0`: 会場 Wi-Fi。API をここで公開する。
- Pi は DJ スイッチ(または DJM の汎用 LAN ポート)に挿す。DJM のチャンネル専用ポートには挿さない(ミキサーが番号を割り当てるため、意図しない番号になる)。
- Pi 上で rekordbox を動かさない。

## 1. Docker Engine

```sh
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker "$USER"
# 再ログインして反映
```

## 2. eth0(DHCP → 失敗したら link-local)

CDJ は既定で auto-IP(169.254/16)。DHCP サーバーがない場合に Pi も 169.254/16 に落ちるようにする。

```sh
nmcli connection show                       # eth0 のプロファイル名を確認(例: "Wired connection 1")
sudo nmcli connection modify "Wired connection 1" \
  ipv4.method auto \
  ipv4.link-local enabled \
  connection.autoconnect-priority 100
sudo nmcli connection up "Wired connection 1"
ip -4 addr show eth0
```

`ipv4.method auto` は DHCP を試し、取れなければ `ipv4.link-local enabled` により 169.254/16 を使う。ネットワークによってはケーブルを繋いでから数十秒かかる。

## 3. wlan0(API 用の固定 IP、例)

```sh
sudo nmcli connection modify "<Wi-Fi の接続名>" \
  ipv4.method manual \
  ipv4.addresses 192.168.10.50/24 \
  ipv4.gateway 192.168.10.1 \
  ipv4.dns 192.168.10.1
sudo nmcli connection up "<Wi-Fi の接続名>"
```

アドレスは会場に合わせて変える。eth0 側にデフォルトゲートウェイを持たせない(DJ Link セグメントはインターネットに出ない)。

## 4. mDNS(`cdj-music-link.local`)

```sh
sudo apt-get install -y avahi-daemon
sudo hostnamectl set-hostname cdj-music-link
sudo systemctl enable --now avahi-daemon
```

クライアントからは `http://cdj-music-link.local:17080/` で到達できる。

## 5. 起動

GHCR のパッケージは既定で private。`pull` の前に次のどちらかを済ませる。

- GitHub のパッケージ設定で `cdj-music-link-server` と `cdj-music-link-fake-cdj` の 2 つを public にする
- Pi 上で `read:packages` だけを持つトークンを使い `docker login ghcr.io -u <GitHubユーザー名>` する

```sh
git clone https://github.com/silmin/cdj-music-link.git
cd cdj-music-link
scripts/live.sh                         # .env を作り、pull → up -d → /v1/health を待って機器一覧を出す
```

`scripts/live.sh` は次と同じ(手で打つならこちら)。

```sh
cp deploy/.env.example deploy/.env      # 必要なら編集
docker compose -f deploy/compose.yaml pull
docker compose -f deploy/compose.yaml up -d
```

`network_mode: host` が必須。Pro DJ Link は UDP のブロードキャスト/ユニキャストを Pi 実インタフェースのアドレスで受けるため、bridge では動かない(`compose.yaml` 参照)。

## 6. 確認

```sh
curl http://localhost:17080/v1/health
docker compose -f deploy/compose.yaml logs -f
```

`online: true` と、取得した `deviceNumber` が返ること。以降の実機確認は `docs/hardware-checklist.md`。

## 更新

```sh
docker compose -f deploy/compose.yaml pull
docker compose -f deploy/compose.yaml up -d
```
