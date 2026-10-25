# cdj-music-link

Pioneer CDJ(Pro DJ Link)のプレイヤー状態を、読み取り専用の HTTP / SSE API として公開するサービス。Raspberry Pi を DJ ネットワークに挿すと、2 台の CDJ-2000NXS2 の再生状態・テンポ・曲情報・拍を、照明や映像などの外部システムから扱える。実機なしで試せる模擬 CDJ 環境も同梱している。

## アーキテクチャ

- `server`: Kotlin / Ktor + [beat-link](https://github.com/Deep-Symmetry/beat-link)。Pro DJ Link に仮想デバイスとして参加し、状態を JSON / SSE にする。
- `fake-cdj`: 模擬 CDJ(keep-alive・ステータス・拍を送出)。実機なしの結合テスト用。
- `openapi/openapi.yaml`: API 仕様。server が `/openapi.yaml` と `/docs`(Swagger UI)で配信する。
- `deploy/compose.yaml`(Pi 用)と `deploy/compose.emu.yaml`(模擬環境用)。
- イメージは GHCR(`ghcr.io/silmin/cdj-music-link-server`、`ghcr.io/silmin/cdj-music-link-fake-cdj`)。

## クイックスタート(模擬環境)

```sh
docker compose -f deploy/compose.emu.yaml up --build
curl localhost:17080/v1/players
curl -N localhost:17080/v1/stream
open http://localhost:17080/docs
```

### 曲名まで出す(模擬 USB)

手元の rekordbox USB を `media`(device 4、[prolink](https://github.com/usr-ein/prolink) の `prolink serve`)として DJ Link に出すと、`fake-cdj-1` のロード曲に曲名・アートワーク・波形・ビートグリッドが付く。USB の `.PIONEER/` は隠しフォルダで、compose はこれを `PIONEER` としても見せるようにマウントする。手順と注意は [`deploy/media/README.md`](deploy/media/README.md)。

```sh
cp deploy/media/.env.example deploy/media/.env      # MEDIA_ROOT と MEDIA_TRACK_ID を編集
docker compose -f deploy/compose.emu.yaml --env-file deploy/media/.env --profile media up --build
curl -s localhost:17080/v1/players/1 | jq .track.title
```

`media` イメージは GPL-3.0 の prolink を含むため、手元の模擬環境専用で、GHCR には上げない。

### 動かし続けて眺める

```sh
scripts/emu-watch.sh [--beats] [--no-scenario] [--keep]
```

模擬環境を起動したまま、SSE のイベントを 1 行ずつ(`HH:MM:SS` 付きで)表示する。`fake-cdj` の 2 台に曲を交互にロードする DJ 風シナリオも既定で流れる(`scenario:` 始まりの行)。`GET /v1/now-playing` の結果が変わると `now playing → player 1  "曲名" — アーティスト` も出る。Ctrl-C で `compose down` して終了。詳細は [`deploy/media/README.md`](deploy/media/README.md)。

## API

ベースパスは `/v1`。時刻は epoch ミリ秒。詳細は `/docs`。

| パス | 返すもの |
|---|---|
| `GET /v1/health` | server の状態、DJ Link に参加しているか(`online`)、仮想デバイス番号(`deviceNumber`) |
| `GET /v1/devices` | DJ Link 上の全機器(CDJ・ミキサー・rekordbox) |
| `GET /v1/players` | プレイヤー一覧 |
| `GET /v1/players/{n}` | プレイヤー 1 台の状態 |
| `GET /v1/players/{n}/track` | ロード中の曲情報(未ロードは 404) |
| `GET /v1/players/{n}/artwork` | ジャケット画像 |
| `GET /v1/players/{n}/waveform/preview` | プレビュー波形 |
| `GET /v1/players/{n}/waveform/detail?fromMs&toMs` | 詳細波形(区間指定) |
| `GET /v1/players/{n}/beatgrid` | ビートグリッド |
| `GET /v1/master` | テンポマスター(いなければ `player` が null) |
| `GET /v1/now-playing` | 今かかっている曲のプレイヤー 1 件、または `null` |
| `GET /v1/stream` | SSE |
| `GET /docs`, `GET /openapi.yaml` | Swagger UI、OpenAPI 仕様 |

### `now-playing` の選び方

- `isOnAir` が取れる環境: on-air かつ再生中のプレイヤーのうちテンポマスターを優先し、なければ番号が小さいもの。
- `isOnAir` が null の環境: 再生中のテンポマスター、なければ再生中のプレイヤーのうち番号が小さいもの。
- 該当なしなら `null`。

### SSE `/v1/stream`

接続直後に必ず `snapshot` が来る。位置の定期送信はない(GET で取得する)。`id:` は単調増加、`retry:` は 2000 ms。

| event | いつ |
|---|---|
| `snapshot` | 接続直後に 1 回(`players`, `master`, `devices`, `online`, `serverTime`) |
| `beat` | 拍ごと |
| `player` | 再生状態・on-air・sync・master・ループのいずれかが変わった |
| `track` | 曲が変わった、またはメタデータが揃った |
| `device` | 機器が現れた / 消えた |
| `master` | テンポマスターが移った |
| `online` | server が DJ Link に参加 / 離脱した |

## 設定(環境変数)

| 変数 | 既定 | 内容 |
|---|---|---|
| `PORT` | `17080` | listen ポート |
| `DEVICE_NAME` | `cdj-music-link` | DJ Link 上の名前 |
| `DEVICE_NUMBER` | 未指定(自動) | 1〜15。未指定なら 1〜4 の空き番号(dbserver メタデータも使う)、埋まっていれば 5 以降(passive) |
| `LOG_LEVEL` | `info` | ログレベル |

## Raspberry Pi への配備

[`deploy/pi-setup.md`](deploy/pi-setup.md) を参照。`network_mode: host` が必須。

## 開発

```sh
./gradlew test               # ユニット・契約テスト
scripts/emu-test.sh          # 模擬環境での結合テスト(Docker、jq が必要)
EMU_MEDIA=1 MEDIA_TRACK_ID=308 scripts/emu-test.sh  # 模擬 USB を足してメタデータ経路も確認
scripts/dev-setup-macos.sh   # macOS での開発セットアップ
```

実機での確認項目は [`docs/hardware-checklist.md`](docs/hardware-checklist.md)。設計は `docs/superpowers/specs/` にある。

## 制約

- PC DJ(自分の PC とオーディオ IF で再生)の曲は Pro DJ Link に出ないため、API からは見えない。
- rekordbox で書き出した USB メディアが前提。
- CDJ-2000NXS2 は 30 ms 位置パケットを出さないため、再生位置は拍からの補間値。
- Pro DJ Link 対応ミキサーが LAN にいなければ `isOnAir` は null。
- 音声レベルは Pro DJ Link に乗らないため取れない。
- 読み取り専用、認証なし。LAN 内での利用が前提。
- beat-link 8.0.0 の既知の問題(ビートグリッドのない曲で TimeFinder が NullPointerException をログに出す)。

## ライセンス

MIT(`LICENSE`)。ただし次の 2 つは Eclipse Public License 2.0(本文は `THIRD_PARTY_LICENSES/EPL-2.0.txt`)。

- `fake-cdj/src/main/kotlin/cdjmusiclink/fakecdj/Packets.kt`: パケットのバイト列テンプレートを beat-link の VirtualCdj から写しているため、このファイル全体を EPL-2.0 で配布する
- `server/src/test/resources/fixtures/sample1/`: Beat Link Trigger 同梱のサンプル曲データ(テスト専用)

`deploy/media/Dockerfile` がビルドする prolink は GPL-3.0-only。リポジトリには Dockerfile だけを置き、イメージは配布しない。

依存ライブラリ(beat-link / crate-digger / electro は EPL-2.0、Ktor / kotlinx / sqlite-jdbc / swagger-ui は Apache-2.0、slf4j / kaitai は MIT)の一覧は `NOTICE` にある。
