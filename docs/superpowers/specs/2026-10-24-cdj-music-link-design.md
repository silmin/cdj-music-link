# cdj-music-link 設計書

日付: 2026-10-24
状態: ドラフト(レビュー前)

## 1. 意図

イベント会場の LAN に置いた Raspberry Pi を Pro DJ Link ネットワークに仮想 CDJ として参加させ、CDJ-2000NXS2 が流してくる情報(再生状態・テンポ・拍・再生位置・曲情報・波形・ジャケット)を、読み取り専用の HTTP API と SSE ストリームとして会場内に公開する。

API の利用者は固定ではない。「今かかっている曲を表示する」「曲名で Spotify を検索してリンクを出す」「拍と波形で照明を動かす」など、会場内の誰かが自分のやり方で使う。だから API は小さく、説明なしで読めて、curl で試せることを優先する。

## 2. ゴール / 非ゴール / アンチゴール

**ゴール**

- Pi が CDJ に認識され、2 台の NXS2 と rekordbox link 1 台の状態を取り続ける。
- `GET` で現在状態、`SSE` で「1 秒に 1 回ポーリングしても間に合わないもの」だけを流す。
- OpenAPI 3 の仕様をリポジトリに置き、Swagger UI で叩ける。
- 実機なしで、偽 CDJ を使った Docker Compose 環境で端から端まで動作確認できる。
- Pi 上も Docker Compose で動かす。

**非ゴール(v1 ではやらない)**

- CDJ の操作(ロード、再生、テンポ変更、マスター取得)。VirtualCdj の status 送信は使わない。
- Ableton Link / Carabiner。
- Go サービス、認証、API キー、レート制御。必要になったらリバースプロキシか別サービスで足す。
- Rust `prolink serve` によるメタデータ側のエミュレーション(compose の profile として入口だけ残す)。
- CDJ-3000 の 30 ms 位置、Opus Quad、XDJ-AZ の固有対応。beat-link が持っている分は自然に動くが、テストしない。
- 音声レベル。Pro DJ Link には乗らない。

**アンチゴール(これになったら失敗)**

- 「意味のある値を取るには 1 秒に何回も GET しなければならない」API。
- beat-link のクラスがそのまま JSON に漏れて、利用者が beat-link のドキュメントを読まないと分からない API。
- GUI(Beat Link Trigger)や Xvfb が必要な構成。

## 3. 制約と前提

- 機材: CDJ-2000NXS2 ×2、DJM、rekordbox link 1 台。rekordbox 解析済みの USB/SD 再生が主。
- NXS2 は 30 ms 位置パケットを出さない。再生位置は beat-link の `TimeFinder` の補間値を使う。
- NXS2 の dbserver は要求側 device 番号 1〜4 を要求する。既定では番号 5 以降で参加し、メタデータは NFS(Crate Digger)経由のみとする(`MetadataFinder.setPassive(true)`)。CD・未解析曲のタイトルは v1 では取れない。
- Pi: 64bit Raspberry Pi OS、Docker Engine。eth0 を CDJ のスイッチに有線接続、API は同じ NIC か別 NIC どちらでもよいが、DJ Link セグメントに繋がる NIC は 1 枚だけにする。Pi 上で rekordbox を動かさない。
- JVM を書くのは Kotlin の 1 サービスだけ。Clojure も BLT も使わない。
- ライセンス: beat-link は EPL-2.0。このリポジトリは MIT(私の判断。変更可)。

## 4. 構成

```
cdj-music-link/
  openapi/openapi.yaml          API 仕様(spec-first)
  server/                       Kotlin: beat-link ラッパ + HTTP/SSE   ← [A]
  fake-cdj/                     Kotlin: 偽 CDJ(パケット手組み)
  deploy/compose.yaml           本番(Pi): server を network_mode: host
  deploy/compose.emu.yaml       模擬: bridge "djlink" に fake-cdj ×2 + server
  scripts/smoke.sh              curl で一通り叩く
  docs/                         この設計書、実機検証チェックリスト
```

Gradle マルチプロジェクト(Kotlin DSL)、JDK 21、Kotlin 2.x。HTTP は Ktor 3.x(`ktor-server-cio`、`ktor-server-sse`、`ktor-server-cors`、`ktor-server-content-negotiation` + kotlinx.serialization)。Swagger UI は webjar で `/docs` に配信。

依存: `org.deepsymmetry:beat-link:8.0.0`(Maven Central)。slf4j バインディングが必要なので `slf4j-simple`。

### 4.1 server(= [A])

起動順: `DeviceFinder` → `VirtualCdj`(番号は既定で自動 5〜15。環境変数で 1〜4 を指定した場合のみ passive を外す)→ `BeatFinder` → `MetadataFinder(passive)` → `ArtFinder` → `BeatGridFinder` → `WaveformFinder(style=RGB, findDetails=true)` → `TimeFinder`。

`VirtualCdj.start()` は DJ Link 機器を最大 100 秒待ってから失敗する。server は失敗したら数秒待って再試行するループに入り、起動中も HTTP は応答する(`/v1/health` が `online: false` を返す)。機器が全部消えたら(`DeviceAnnouncementListener` の `deviceLost` で `getCurrentDevices()` が空)`VirtualCdj.stop()` して同じループに戻る。これは Beat Link Trigger の `triggers.clj` の device-listener と同じ作法。

状態ストア `PlayerState` はプレイヤー番号ごとに 1 つ。beat-link のリスナー(`DeviceUpdateListener`、`BeatListener`、`TrackMetadataListener`、`AlbumArtListener`、`WaveformListener`、`TrackPositionListener`、`MasterListener`)がこれを更新し、差分があればイベントバスに流す。HTTP/SSE はストアとバスだけを見る。beat-link のオブジェクトを HTTP 層に渡さない。

JSON の形は Beat Link Trigger `overlay.clj` の `describe-device` / `describe-status` / `describe-times` / `format-metadata` を参考に、名前を camelCase に揃える。

### 4.2 公開 API(v1)

ベースパス `/v1`。すべて JSON、UTF-8、CORS は `*`、認証なし。時刻はすべて epoch ミリ秒(Pi の時計)。

| メソッド/パス | 返すもの |
|---|---|
| `GET /v1/health` | `{online, deviceNumber, deviceCount, serverTime, version}` |
| `GET /v1/devices` | DJ Link 上の全機器(CDJ、ミキサー、rekordbox)。`{number, name, address, kind}` |
| `GET /v1/players` | プレイヤー一覧(`kind = player` のみ)。各要素は `/players/{n}` と同じ |
| `GET /v1/players/{n}` | 下記 Player |
| `GET /v1/players/{n}/track` | Player の `track` 部分だけ。曲がなければ 404 |
| `GET /v1/players/{n}/artwork` | ジャケット画像のバイト列(`Content-Type` は実データから判定)。なければ 404 |
| `GET /v1/players/{n}/waveform/preview` | `{style, segments, maxHeight, heights:[int], colors:["#rrggbb"]}` |
| `GET /v1/players/{n}/waveform/detail?fromMs=&toMs=` | `{style, framesPerSecond:150, fromMs, toMs, heights:[int], colors:["#rrggbb"]}`。範囲省略時は全体 |
| `GET /v1/players/{n}/beatgrid` | `{beats:[{number, ms, beatWithinBar, bpm}]}` |
| `GET /v1/master` | テンポマスターの `{player, tempo}`。いなければ `{player: null}` |
| `GET /v1/now-playing` | 「今かかっている曲」。Player 1 件か `null` |
| `GET /v1/stream` | SSE |
| `GET /docs`, `GET /openapi.yaml` | Swagger UI と仕様 |

**Player**

```json
{
  "number": 2, "name": "CDJ-2000nexus", "address": "192.168.1.12",
  "updatedAt": 1761300000000,
  "playState": "playing",            // playing | paused | cued | searching | loading | ended | empty
  "isPlaying": true, "isPaused": false, "isCued": false, "isLooping": false,
  "isOnAir": true, "isSynced": false, "isTempoMaster": true,
  "trackBpm": 128.0, "pitch": 1.25, "tempo": 129.6,
  "beatNumber": 412, "beatWithinBar": 1,
  "position": { "ms": 193125, "remainingMs": 171003, "updatedAt": 1761300000000, "definitive": true },
  "track": {
    "id": 1742, "sourcePlayer": 2, "slot": "usb", "type": "rekordbox",
    "title": "...", "artist": "...", "album": "...", "key": "8A", "genre": "...",
    "label": "...", "remixer": "...", "originalArtist": "...", "comment": "...",
    "durationSec": 364, "startingBpm": 128.0, "rating": 0, "year": 2021, "dateAdded": "2021-05-01",
    "color": { "name": "Pink", "hex": "#ff5aa8" },
    "artworkAvailable": true, "waveformAvailable": true, "beatgridAvailable": true
  }
}
```

`track` はロード直後は `id`・`sourcePlayer`・`slot`・`type` だけが入り、タイトル等は NFS 取得後に埋まる。利用者はフィールドの有無で判断できる。未ロードなら `track: null`。

**now-playing の判定(私の判断、変更可)**: テンポマスターが再生中ならそれ。いなければ on-air かつ再生中のプレイヤーのうち番号が小さいもの。いなければ再生中のプレイヤー。いなければ `null`。

### 4.3 SSE `/v1/stream`

接続直後に `snapshot`(`/v1/players` と `/v1/master` の内容)を 1 回送る。以後は次のイベントだけ。位置の定期送信はしない(GET で取れる)。

| event | いつ | data |
|---|---|---|
| `beat` | 拍ごと | `{player, beatNumber, beatWithinBar, tempo, nextBeatInMs, nextBarInMs, isTempoMaster, position:{ms}, waveform:{height, color}}`。`waveform` はプレビュー波形の現在位置の列(照明用の音量代替)。波形未取得なら `null` |
| `player` | playState / onAir / sync / master / loop のいずれかが変わった | Player 全体 |
| `track` | 曲が変わった、またはメタデータが揃った | `{player, track}` |
| `device` | 機器が現れた / 消えた | `{action: "connected"|"disconnected", device}` |
| `master` | テンポマスターが移った | `{player, tempo}` |
| `online` | server が DJ Link に参加 / 離脱 | `{online}` |

`id:` には単調増加の連番、`retry:` は 2000 ms。

### 4.4 fake-cdj(模擬環境)

beat-link の `VirtualCdj` は「他の DJ Link 機器が見えるまで start しない」ので、空のネットワークで最初の 1 台になれない。したがって偽 CDJ は beat-link に依存せず、解析ドキュメントの配置に従ってパケットを手組みする。テンプレートは beat-link `VirtualCdj.java` の `keepAliveBytes` / `STATUS_PAYLOAD` / `BEAT_PAYLOAD` を参照する(EPL-2.0 なので出典を記す)。

- 50000: 1.5 秒ごとにキープアライブを broadcast。50000 を listen して他機器のアドレスを覚える。
- 50002: 200 ms ごとに、覚えた各機器へ unicast でステータス。再生/停止、テンポ×100、拍番号、小節内拍、master/sync/on-air、**track ID とスロット**(ここは VirtualCdj では変えられないが、偽 CDJ では変えられる)。
- 50001: 再生中、拍ごとに broadcast でビートパケット。次拍・次小節までの ms を載せる。
- シナリオ制御: 環境変数(番号、名前、BPM、trackId、slot、自動再生)と、テスト用の小さな HTTP(`POST /play`, `/pause`, `/tempo`, `/load`, `/master`)。
- 検証: 組んだバイト列を beat-link の `DeviceAnnouncement` / `CdjStatus` / `Beat` でパースできることをユニットテストで保証する。

メタデータ(NFS/dbserver)は模擬しない。server 側の `track` はロード直後の状態(`id`/`slot`/`type` のみ)で止まる。

### 4.5 Docker

- `server/Dockerfile`: multi-stage(gradle build → `eclipse-temurin:21-jre`)。`linux/arm64` と `linux/amd64`。
- `deploy/compose.yaml`(Pi): `server` を `network_mode: host`、`restart: unless-stopped`、環境変数で `PORT`(既定 17080)、`DEVICE_NAME`、`DEVICE_NUMBER`(省略で自動)、`LOG_LEVEL`。
- `deploy/compose.emu.yaml`: user-defined bridge `djlink`。`fake-cdj-1`(番号 1、128 BPM、自動再生)、`fake-cdj-2`(番号 2、停止)、`server`(ポートを host に publish)。
- Docker Desktop(macOS)で bridge 内のコンテナ間 UDP broadcast が通ることを、実装の最初に `socat` で確認する。通らなければ Linux VM か Pi 実機で模擬を回す。

### 4.6 OpenAPI

`openapi/openapi.yaml` を正とする(spec-first)。server は `/openapi.yaml` でそのまま配信し、`/docs` に Swagger UI。契約テストとして、server をインプロセス起動し、各エンドポイントのレスポンスを仕様に対して検証する(ライブラリはビルダー選択。例: swagger-request-validator)。SSE はイベント名と data の JSON スキーマを仕様の `components` に書き、同じテストで検証する。

## 5. テスト

- ユニット: fake-cdj のパケットを beat-link クラスでパース。JSON 整形は beat-link の生データクラス(`BeatGrid`、`WaveformPreview`、`WaveformDetail` はバイト列から構築可)に BLT 同梱 `resources/beat_link_trigger/sampleTracks` のバイナリを食わせて検証。`TrackMetadata` はバイト列から作れないので、`TrackInfo` DTO への変換は実機検証に回し、DTO 以降を単体で検証する。
- 契約: OpenAPI に対するレスポンス検証。
- 統合(compose.emu): server が 2 台を認識し、`/v1/players` に 2 件、1 番が `playing` で `tempo ≈ 128`、SSE の `beat` が約 468 ms 間隔で届く、`fake-cdj-2` に `POST /play` すると `player` イベントが出る、`fake-cdj` を両方止めると `online: false` になり、戻すと復帰する。
- 実機: `docs/hardware-checklist.md`。tcpdump で 50000〜50002、`/v1/health` の `online`、曲ロードから `track.title` が埋まるまでの秒数、2 台同時再生、rekordbox link の出入り。

## 6. ビルダーに任せる点

- Ktor のバージョン固定、Gradle の設定、Kotlin のパッケージ構成。
- SSE のバックプレッシャー(遅いクライアントの切断方針)。
- 波形 detail の最大レスポンスサイズと分割の要否。
- 契約テストのライブラリ。
- fake-cdj の制御 HTTP の正確なパス名。

## 7. 私の判断として置いたもの(変更可)

- リポジトリは MIT。
- 既定ポート 17080(BLT の 17081 と被らない)。
- `MetadataFinder` は既定で passive。番号 1〜4 を明示したときだけ dbserver を使う。
- now-playing の判定規則。
- SSE で位置の定期配信をしない。
- 波形は数値配列で返し、PNG は出さない。
- fake-cdj は beat-link に依存しない手組み。
