# 公開デモ(Cloudflare Tunnel)

模擬 CDJ 2 台 + 模擬 USB + server を Linux ホストで動かし続け、server の API だけを Cloudflare Tunnel 越しに HTTPS で公開する構成。USB の曲が順にかかっている状態を、開発用のデータとして誰でも読める。構成は [`compose.public.yaml`](compose.public.yaml)。

| サービス | 役割 |
|---|---|
| `fake-cdj-1` / `fake-cdj-2` | 模擬 CDJ |
| `media` | 模擬 rekordbox USB(device 4)。詳細は [`media/README.md`](media/README.md) |
| `server` | API |
| `scenario` | DJ 役。USB の曲を 2 台に交互にロード・再生し続ける([`media/scenario.sh`](media/scenario.sh)) |
| `cloudflared` | Tunnel の出口。`server:17080` を Cloudflare につなぐ |

ホストにはポートを公開しない。fake-cdj の操作 API は認証がないので、compose のネットワーク内(`scenario`)からしか呼べない。全サービスが `restart: unless-stopped` で、ホストの再起動後も docker が上がれば戻る。

## 公開されるもの

API は読み取り専用・認証なしで、USB に入っている曲の曲名・アーティスト・ジャケット・波形・ビートグリッドがそのまま返る。公開してよい曲だけを入れた USB(またはその複製)を使う。

## 準備

1. ホストに docker と compose plugin を入れ、このリポジトリを clone する。
2. USB の中身をホストに置く。音源は不要(`--audio 0`)。

   ```sh
   scripts/media-snapshot.sh --audio 0 /Volumes/<USB> /tmp/rekordbox-usb   # 手元で
   rsync -a /tmp/rekordbox-usb/ <host>:/srv/rekordbox-usb/
   ```

3. Cloudflare で Tunnel(cloudflared 方式)を作り、トークンを控える。Public Hostname を 1 つ足し、Service を `http://server:17080` にする。
4. 設定ファイルを作る。

   ```sh
   cp deploy/public.env.example deploy/public.env   # MEDIA_ROOT と TUNNEL_TOKEN を編集
   ```

## 起動と確認

```sh
docker compose -f deploy/compose.public.yaml --env-file deploy/public.env up -d --build
docker compose -f deploy/compose.public.yaml logs -f scenario     # load / play / pause が流れる
curl -s https://<公開ホスト名>/v1/now-playing | jq '.track.title'
curl -N https://<公開ホスト名>/v1/stream
```

`media` イメージ(GPL-3.0 の prolink を含む)は初回にホスト上でビルドされる。Rust のビルドなので数分かかる。`server` と `fake-cdj` は GHCR から pull する。

更新は `git pull` のあと次を実行する。

```sh
docker compose -f deploy/compose.public.yaml --env-file deploy/public.env pull
docker compose -f deploy/compose.public.yaml --env-file deploy/public.env up -d --build
```

## 設定(`deploy/public.env`)

| 変数 | 既定 | 内容 |
|---|---|---|
| `MEDIA_ROOT` | なし(必須) | USB の中身を置いた場所(`.PIONEER/` と `Contents/` がある場所) |
| `TUNNEL_TOKEN` | なし(必須) | Cloudflare Tunnel のトークン |
| `SWITCH_SEC` | `45` | 曲を切り替える間隔(秒) |
| `OVERLAP_SEC` | `12` | 2 台が同時に再生している時間(秒) |
| `SHUFFLE` | `1` | `1` なら周回ごとにシャッフル、`0` なら export 順 |

## 実機との違い

- `media` も `/v1/players` に 1 台として出る(合計 3 台)。
- 波形はブルー(`style: blue`、`segments: 450`)。理由は [`media/README.md`](media/README.md)。
- 曲は最後まで再生されず、`SWITCH_SEC` ごとに次の曲へ切り替わる。
- テンポは曲の BPM(`track.startingBpm`)に固定で、ピッチは常に 0。
- 一部の曲は beat-link がメタデータを読めず(server のログに `Hot cues must have non-zero numbers`)、曲名が付かない。`scenario` はそういう曲を再生せずに飛ばす(ログに `no metadata for id=…, skipping`)。
- `isOnAir` は null(ミキサーがいない)。
