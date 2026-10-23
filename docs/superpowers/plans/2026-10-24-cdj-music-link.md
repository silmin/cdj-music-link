# cdj-music-link Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Raspberry Pi 上で beat-link を使って CDJ-2000NXS2 の状態を取り、読み取り専用の HTTP/SSE API として公開する Kotlin サービスと、実機なしで検証できる偽 CDJ と Docker Compose 環境を作る。

**Architecture:** `server` モジュールが beat-link の Finder 群をリスナーで購読して `PlayerState` に畳み込み、Ktor で `/v1/*` と SSE を出す。`fake-cdj` モジュールは beat-link に依存せず、解析ドキュメントの配置でキープアライブ・ステータス・ビートのパケットを手組みして送る。`openapi/openapi.yaml` が API の正で、契約テストがレスポンスを検証する。

**Tech Stack:** Eclipse Temurin 25(LTS)、Kotlin 2.4.21、Gradle 9.7.1(wrapper、Kotlin DSL、バージョンカタログ)、Ktor 3.6.0(cio / sse / cors / content-negotiation / test-host)、kotlinx.serialization 1.12.0、kotlinx.coroutines 1.11.0、beat-link 8.0.0、slf4j-simple 2.0.20、JUnit 6.1.3(Jupiter)、swagger-request-validator-core 3.0.0(JDK 25 で動かなければ 2.44.x)、swagger-ui webjar 5.34.0、Docker / Compose、GitHub Actions。

**Spec:** `docs/superpowers/specs/2026-10-24-cdj-music-link-design.md`

## Global Constraints

- ランタイムとツールチェーンは JDK 25(Temurin)。`kotlin { jvmToolchain(25) }`。beat-link 8.0.0 は Java 11 クラスファイルなので問題ない。Gradle は wrapper 9.7.1 に固定(Kotlin 2.4.21 の対応上限が Gradle 9.7)。
- `VirtualCdj.setSendingStatus` は呼ばない(CDJ を操作しない)。
- 既定で `VirtualCdj.setUseStandardPlayerNumber(true)`。取得した番号が 4 以下なら `MetadataFinder.setPassive(false)`、5 以上なら `setPassive(true)`。
- 公開 API のベースパスは `/v1`、CORS は `*`、認証なし、時刻は epoch ミリ秒。既定ポート 17080。
- `isOnAir` はミキサー機器(device 番号 0x21〜0x28)が DeviceFinder に見えないとき `null`。
- SSE で位置の定期配信はしない。イベントは `snapshot` / `beat` / `player` / `track` / `device` / `master` / `online` のみ。
- 波形は数値配列で返す。PNG は出さない。
- beat-link のクラスを HTTP 層に渡さない(DTO に変換する)。
- fake-cdj は beat-link に実行時依存しない(テストでは使ってよい)。
- Pi 上は `network_mode: host`、模擬環境は user-defined bridge `djlink`。
- ライセンス MIT。beat-link(EPL-2.0)由来のテンプレートとフィクスチャは `NOTICE` に出典を書く。

## Review Focus

1. ステータスが来ているのに `TimeFinder` がまだ位置を持たない区間(曲ロード直後、ビートグリッド未取得): `position` が `null` で JSON が壊れないこと。Task 4 の `PlayerStateTest.positionIsNullBeforeTimeFinderKnows` で固定。
2. 偽 CDJ が 2 台とも消えたあと戻ってきたとき: `online` が false → true に戻り、`/v1/players` が再び埋まること。Task 10 の統合スクリプトで固定。
3. CDJ が曲をアンロードしたとき(`isTrackLoaded == false`): `track` が `null` に戻り、`track` イベントが `track: null` で出ること。Task 4 の `PlayerStateTest.trackBecomesNullOnUnload` で固定。
4. 波形 detail の `fromMs`/`toMs` が範囲外・逆順・非数のとき: 400 を返し、JSON の `error` に理由が入ること。Task 6 の `WaveformRoutesTest.detailRejectsBadRange` で固定。
5. SSE クライアントが遅いとき: サーバーのリスナーがブロックせず、遅いクライアント側で古いイベントが落ちること。Task 4 の `EventBusTest.slowSubscriberDropsOldest` で固定。

---

## File Structure

```
cdj-music-link/
  settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml, gradlew
  openapi/openapi.yaml
  server/
    build.gradle.kts, Dockerfile
    src/main/kotlin/cdjmusiclink/server/
      Main.kt                      起動、設定読み込み、HTTP 起動
      Config.kt                    環境変数 → Config
      model/Dto.kt                 Player, Track, Position, Device, MasterInfo, Waveform*, BeatGrid*(kotlinx.serialization)
      model/Enums.kt               PlayState, Slot, TrackType, DeviceKind と beat-link enum からの変換
      state/PlayerState.kt         プレイヤー 1 台分の可変状態と DTO 化
      state/Store.kt               全プレイヤー・機器・マスターを保持、EventBus へ差分通知
      state/EventBus.kt            SharedFlow ベースのイベントバス
      state/NowPlaying.kt          now-playing 判定
      link/LinkSession.kt          beat-link Finder の起動/停止/再試行ループ
      link/Listeners.kt            beat-link リスナー → Store
      link/Conversions.kt          beat-link オブジェクト → DTO(波形、ビートグリッド、メタデータ)
      http/Routes.kt               /v1/* ルート
      http/Sse.kt                  /v1/stream
      http/Docs.kt                 /docs, /openapi.yaml
      http/Errors.kt               400/404 の JSON
    src/test/kotlin/cdjmusiclink/server/...   各ファイルに対応するテスト
    src/test/resources/fixtures/sample1/      preview-color.data, detail-color.data, beat-grid.json, art.jpg
  fake-cdj/
    build.gradle.kts, Dockerfile
    src/main/kotlin/cdjmusiclink/fakecdj/
      Packets.kt                   keepAlive / status / beat のバイト列生成
      Deck.kt                      再生状態(再生中、テンポ、拍、track)と時間進行
      Peers.kt                     50000 を listen して他機器のアドレスを集める
      FakeCdj.kt                   送信ループ(1.5 s / 200 ms / 拍)
      Control.kt                   制御 HTTP(POST /play 等)
      Main.kt
    src/test/kotlin/cdjmusiclink/fakecdj/   PacketsTest(beat-link でパース)、DeckTest
  deploy/compose.yaml, deploy/compose.emu.yaml, deploy/pi-setup.md
  scripts/smoke.sh, scripts/emu-test.sh
  .github/workflows/ci.yml
  README.md, NOTICE, LICENSE
  docs/hardware-checklist.md
```

---

### Task 0: ツールチェーンと Gradle 骨組み

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `server/build.gradle.kts`, `fake-cdj/build.gradle.kts`, `.gitignore`, `LICENSE`(MIT)、`NOTICE`
- Create: `server/src/main/kotlin/cdjmusiclink/server/Main.kt`(`fun main()` が "cdj-music-link server" を出力するだけ)
- Create: `fake-cdj/src/main/kotlin/cdjmusiclink/fakecdj/Main.kt`(同様)
- Test: `server/src/test/kotlin/cdjmusiclink/server/SmokeTest.kt`

**Interfaces:**
- Produces: Gradle モジュール `:server`, `:fake-cdj`。バージョンカタログの alias(`libs.ktor.server.cio` など)。

判断(実装者が従う): この Mac の既存 JDK は全部 x86_64 なので撤去済み(別途実施)。`brew install --cask temurin@25` と `brew install gradle`(wrapper 生成用)を前提にする。`gradle wrapper --gradle-version 9.7.1` で wrapper を作り、以後は `./gradlew` のみ使う。バージョンカタログは Tech Stack の値を固定で書く。ルートパッケージは `cdjmusiclink`。

- [ ] **Step 1: JDK 25 と Gradle を確認** — Run: `/usr/libexec/java_home -v 25 && java -version && gradle --version | head -3` Expected: Temurin 25 のパス、`openjdk version "25`、Gradle 9.8 以上(wrapper 生成にだけ使う)
- [ ] **Step 2: Gradle 骨組みを書く**(`gradle wrapper --gradle-version 9.7.1` → `settings.gradle.kts` に `include("server", "fake-cdj")`、`libs.versions.toml`、両モジュールに `kotlin("jvm")`、`kotlin("plugin.serialization")`、`application`、`kotlin { jvmToolchain(25) }`、JUnit 6(Jupiter)と `useJUnitPlatform()`。`:server` に ktor / serialization / coroutines / beat-link / slf4j-simple / swagger-request-validator-core / swagger-ui webjar / ktor-server-test-host。`:fake-cdj` の main 依存は coroutines と serialization のみ、test 依存に beat-link)
- [ ] **Step 3: `SmokeTest.kt` に `@Test fun jvmIs25() { assertEquals("25", System.getProperty("java.specification.version")) }`**
- [ ] **Step 4: Run** `./gradlew test` Expected: BUILD SUCCESSFUL、SmokeTest PASS
- [ ] **Step 5: Homebrew の gradle を外す** — wrapper ができたので `brew uninstall gradle && brew autoremove`(gradle が依存で入れた openjdk 27 も消える)。Run: `./gradlew --version | grep -E 'Gradle|JVM'` Expected: Gradle 9.7.1、JVM 25
- [ ] **Step 6: Commit** `chore: gradle scaffold for server and fake-cdj`

---

### Task 1: OpenAPI 仕様

**Files:**
- Create: `openapi/openapi.yaml`

**Interfaces:**
- Produces: `components.schemas` の `Health, Device, Player, Track, Position, Color, MasterInfo, WaveformPreview, WaveformDetail, BeatGrid, Error, SseSnapshot, SseBeat, SsePlayer, SseTrack, SseDevice, SseMaster, SseOnline`。後続タスクの DTO はこれと同じフィールド名・型にする。

- [ ] **Step 1: `openapi.yaml` を書く**。パスは設計書 4.2 の表の通り。`Player` のフィールドは設計書の JSON 例の通りで、`isOnAir` は `type: boolean, nullable: true`、`track` と `position` は nullable。`playState` enum は `playing, paused, cued, searching, loading, ended, empty`。`slot` enum は `usb, sd, cd, collection, none`。`type` enum は `rekordbox, unanalyzed, cd, none`。`WaveformPreview` は `{style: string(enum blue,rgb,threeBand), segments: int, maxHeight: int, heights: int[], colors: string[]}`。`WaveformDetail` は `{style, framesPerSecond: 150, fromMs, toMs, heights, colors}`。`BeatGrid` は `{beats: [{number, ms, beatWithinBar, bpm}]}`。`MasterInfo` は `{player: int|null, tempo: number|null}`。`Error` は `{error: string}`。`GET /v1/stream` は `text/event-stream` を返すと書き、各 `Sse*` スキーマを `components` に置く(`SseBeat` は `{player, beatNumber, beatWithinBar, tempo, nextBeatInMs, nextBarInMs, isTempoMaster, position:{ms}|null, waveform:{height:int, color:string}|null, receivedAt}`)。`x-sse-events` 拡張に event 名 → スキーマ名の対応を書く。
- [ ] **Step 2: 構文検証** — Run: `npx -y @redocly/cli@1 lint openapi/openapi.yaml` Expected: エラー 0(警告は可)
- [ ] **Step 3: Commit** `docs: openapi v1 spec`

---

### Task 2: DTO と enum 変換

**Files:**
- Create: `server/src/main/kotlin/cdjmusiclink/server/model/Dto.kt`, `model/Enums.kt`
- Test: `server/src/test/kotlin/cdjmusiclink/server/model/EnumsTest.kt`, `DtoJsonTest.kt`

**Interfaces:**
- Produces: `@Serializable data class Player(number:Int, name:String, address:String, updatedAt:Long, playState:PlayState, isPlaying:Boolean, isPaused:Boolean, isCued:Boolean, isLooping:Boolean, isOnAir:Boolean?, isSynced:Boolean, isTempoMaster:Boolean, trackBpm:Double?, pitch:Double, tempo:Double?, beatNumber:Int?, beatWithinBar:Int?, position:Position?, track:Track?)`
- `Track(id:Int, sourcePlayer:Int, slot:Slot, type:TrackType, title:String?, artist:String?, album:String?, key:String?, genre:String?, label:String?, remixer:String?, originalArtist:String?, comment:String?, durationSec:Int?, startingBpm:Double?, rating:Int?, year:Int?, dateAdded:String?, color:Color?, artworkAvailable:Boolean, waveformAvailable:Boolean, beatgridAvailable:Boolean)`
- `Position(ms:Long, remainingMs:Long?, updatedAt:Long, definitive:Boolean)`, `Color(name:String, hex:String)`, `Device(number:Int, name:String, address:String, kind:DeviceKind)`, `MasterInfo(player:Int?, tempo:Double?)`, `WaveformPreviewDto`, `WaveformDetailDto`, `BeatGridDto`, `Health`, `ErrorDto(error:String)`
- enum `PlayState`, `Slot`, `TrackType`, `DeviceKind(player, mixer, collection)` と関数 `fun playStateOf(s: CdjStatus): PlayState`, `fun slotOf(s: CdjStatus.TrackSourceSlot): Slot`, `fun trackTypeOf(t: CdjStatus.TrackType): TrackType`, `fun deviceKindOf(number: Int): DeviceKind`(0x10 未満 player、0x21〜0x28 mixer、他 collection。BLT `overlay.clj` の `device-kind` と同じ)
- kotlinx.serialization の `Json` 設定: `encodeDefaults = true, explicitNulls = true`

- [ ] **Step 1: テストを書く** — `EnumsTest.deviceKindBoundaries`(1→player, 0x21→mixer, 0x28→mixer, 0x11→collection, 0x29→collection)、`EnumsTest.playStateFromFlags`(`isPlaying` true → playing、`isPaused` → paused、`isCued` → cued、`isSearching` → searching、`isTrackLoaded` false → empty、`isAtEnd` → ended、どれでもなく `isBusy` → loading。CdjStatus はモックできないので判定を `fun playStateOf(playing:Boolean, paused:Boolean, cued:Boolean, searching:Boolean, atEnd:Boolean, busy:Boolean, loaded:Boolean)` に分け、CdjStatus 版はそれを呼ぶ)、`DtoJsonTest.playerSerializesNullsExplicitly`(`isOnAir=null`、`track=null` が JSON に `null` として出る)
- [ ] **Step 2: Run** `./gradlew :server:test --tests 'cdjmusiclink.server.model.*'` Expected: コンパイルエラーで FAIL
- [ ] **Step 3: `Dto.kt` と `Enums.kt` を実装**
- [ ] **Step 4: Run** 同コマンド Expected: PASS
- [ ] **Step 5: Commit** `feat(server): api dtos and enum conversions`

---

### Task 3: beat-link データの DTO 変換(波形・ビートグリッド・メタデータ)

**Files:**
- Create: `server/src/main/kotlin/cdjmusiclink/server/link/Conversions.kt`
- Create: `server/src/test/resources/fixtures/sample1/{preview-color.data, detail-color.data, beat-grid.json, art.jpg}`、`NOTICE` に出典追記
- Test: `server/src/test/kotlin/cdjmusiclink/server/link/ConversionsTest.kt`

**Interfaces:**
- Consumes: Task 2 の DTO
- Produces: `fun WaveformPreview.toDto(): WaveformPreviewDto`、`fun WaveformDetail.toDto(fromMs: Long?, toMs: Long?): WaveformDetailDto`、`fun BeatGrid.toDto(): BeatGridDto`、`fun TrackMetadata.toTrack(ref: DataReference, artwork:Boolean, waveform:Boolean, beatgrid:Boolean): Track`、`fun WaveformPreview.columnAt(ms: Long, totalMs: Long): Pair<Int, String>`(拍イベントの `waveform` 用。`segment = ms * segmentCount / totalMs`)、`fun colorHex(c: java.awt.Color): String`

判断: BLT 同梱 `resources/beat_link_trigger/sampleTracks/1` の `preview-color.data`(7200 B)、`detail-color.data`、`art.jpg` をそのままコピーし、`beat-grid.edn`(`[[beatWithinBar...] [timeMs...] [bpm...]]` の 3 ベクタ)は JSON に変換して `beat-grid.json`(`{"beatWithinBar":[...],"timeMs":[...],"bpm":[...]}`)として置く。変換は 1 回だけ Python で行い、スクリプトは残さない。

- [ ] **Step 1: フィクスチャをコピー・変換し、`NOTICE` に「Sample track data from Beat Link Trigger (Deep Symmetry, EPL-2.0)」を追記**
- [ ] **Step 2: テストを書く** — `previewToDtoHas1200ColorColumns`(`WaveformPreview(DataReference(1, USB_SLOT, 1), ByteBuffer.wrap(bytes), WaveformStyle.RGB)` → `segments == 1200`、`heights.size == 1200`、`colors[0]` が `#` + 6 桁 hex)、`detailToDtoRangeClips`(`fromMs=1000, toMs=2000` → `heights.size == 150`)、`beatGridToDtoFirstBeat`(`BeatGrid(ref, beatWithinBar, bpm, timeMs)` → `beats[0].number == 1`、`beats[0].beatWithinBar == 1`)、`columnAtMidTrack`(`columnAt(totalMs/2, totalMs).first` が 0 以上 `maxHeight` 以下)、`colorHexFormatsRed`(`Color(255,0,0)` → `"#ff0000"`)
- [ ] **Step 3: Run** `./gradlew :server:test --tests 'cdjmusiclink.server.link.ConversionsTest'` Expected: FAIL
- [ ] **Step 4: `Conversions.kt` を実装**。`toTrack` は `TrackMetadata` の getter(`getTitle, getArtist?.label, getAlbum?.label, getKey?.label, getGenre?.label, getLabel?.label, getRemixer?.label, getOriginalArtist?.label, getComment, getDuration, getTempo/100.0, getRating, getYear, getDateAdded, getColor`)を写す。`slot`/`type` は `ref.slot`/`ref.trackType` から Task 2 の変換で。
- [ ] **Step 5: Run** 同コマンド Expected: PASS
- [ ] **Step 6: Commit** `feat(server): convert beat-link waveform, beat grid and metadata to dtos`

---

### Task 4: 状態ストアとイベントバス

**Files:**
- Create: `server/src/main/kotlin/cdjmusiclink/server/state/{EventBus.kt, PlayerState.kt, Store.kt}`
- Test: `server/src/test/kotlin/cdjmusiclink/server/state/{EventBusTest.kt, PlayerStateTest.kt, StoreTest.kt}`

**Interfaces:**
- Produces:
  - `sealed interface Event { val id: Long }` と `data class BeatEvent(...)`, `PlayerEvent(player: Player)`, `TrackEvent(player:Int, track: Track?)`, `DeviceEvent(action:String, device: Device)`, `MasterEvent(player:Int?, tempo:Double?)`, `OnlineEvent(online:Boolean)`(すべて `@Serializable`、Task 1 の `Sse*` と同じフィールド)
  - `class EventBus { val events: SharedFlow<Event>; fun publish(e: Event) }`(`MutableSharedFlow(replay=0, extraBufferCapacity=256, onBufferOverflow=DROP_OLDEST)`。`publish` は `tryEmit`)
  - `class PlayerState(number:Int, name:String, address:String)` に `fun applyStatus(s: StatusSnapshot, mixerPresent:Boolean, now:Long): Set<Change>`、`fun applyPosition(ms:Long, definitive:Boolean, totalMs:Long?, now:Long)`、`fun applyMetadata(track: Track?)`、`fun applyAvailability(artwork:Boolean?, waveform:Boolean?, beatgrid:Boolean?)`、`fun toDto(): Player`。`StatusSnapshot` は CdjStatus から取り出した純データ(`playing, paused, cued, looping, searching, atEnd, busy, trackLoaded, onAir, synced, master, bpmX100:Int, pitchPct:Double, effectiveTempo:Double?, beatNumber:Int?, beatWithinBar:Int?, rekordboxId:Int, sourcePlayer:Int, slot:Slot, type:TrackType`)。`Change` は `enum { PLAY_STATE, FLAGS, TRACK, MASTER }`
  - `class Store(bus: EventBus, clock: () -> Long)` に `fun upsertDevice(d: Device)`, `fun removeDevice(number:Int)`, `fun devices(): List<Device>`, `fun players(): List<Player>`, `fun player(n:Int): Player?`, `fun status(n:Int, s: StatusSnapshot)`, `fun position(n:Int, ms:Long, definitive:Boolean, totalMs:Long?)`, `fun metadata(n:Int, track: Track?)`, `fun master(player:Int?, tempo:Double?)`, `fun masterInfo(): MasterInfo`, `fun online(flag:Boolean)`, `fun mixerPresent(): Boolean`, `fun beat(n:Int, e: BeatEvent)`(そのまま publish)

- [ ] **Step 1: テストを書く** — `EventBusTest.slowSubscriberDropsOldest`(購読者が collect しないまま 300 件 publish → 購読再開後に受け取る最初の id が 44 以上、つまり古い方が落ちる)、`PlayerStateTest.positionIsNullBeforeTimeFinderKnows`(status だけ適用 → `toDto().position == null`)、`PlayerStateTest.trackBecomesNullOnUnload`(trackLoaded=true,id=5 → `track.id == 5`、次に trackLoaded=false → `track == null` かつ戻り値に `TRACK`)、`PlayerStateTest.onAirNullWithoutMixer`(`mixerPresent=false` → `isOnAir == null`)、`StoreTest.statusChangeEmitsPlayerEventOnlyOnChange`(同じ snapshot を 2 回 → `PlayerEvent` は 1 回)、`StoreTest.metadataEmitsTrackEvent`(`metadata(1, track)` → `TrackEvent(player=1, track=track)`、`metadata(1, null)` → `TrackEvent(1, null)`)、`StoreTest.removeDeviceEmitsDisconnected`
- [ ] **Step 2: Run** `./gradlew :server:test --tests 'cdjmusiclink.server.state.*'` Expected: FAIL
- [ ] **Step 3: 実装**。`tempo` は `effectiveTempo`、`trackBpm` は `bpmX100 == 0xffff` なら `null`。`remainingMs = totalMs - ms`(`totalMs` 不明なら `null`)。
- [ ] **Step 4: Run** 同コマンド Expected: PASS
- [ ] **Step 5: Commit** `feat(server): player state store and event bus`

---

### Task 5: beat-link セッション(起動・リスナー・再試行)

**Files:**
- Create: `server/src/main/kotlin/cdjmusiclink/server/link/{LinkSession.kt, Listeners.kt}`, `Config.kt`
- Test: `server/src/test/kotlin/cdjmusiclink/server/link/ListenersTest.kt`, `ConfigTest.kt`

**Interfaces:**
- Consumes: Task 4 の `Store`、Task 3 の変換
- Produces:
  - `data class Config(port:Int=17080, deviceName:String="cdj-music-link", deviceNumber:Int?=null, logLevel:String="info")` と `fun Config.Companion.fromEnv(env: Map<String,String>): Config`(`PORT`, `DEVICE_NAME`, `DEVICE_NUMBER`, `LOG_LEVEL`)
  - `class LinkSession(config: Config, store: Store, scope: CoroutineScope)` に `fun start()`, `fun stop()`, `val online: StateFlow<Boolean>`, `val deviceNumber: Int?`
  - `object Listeners` に純関数 `fun snapshotOf(s: CdjStatus): StatusSnapshot` と、各リスナーを生成する `fun attach(store: Store, scope: CoroutineScope): Attached`(`DeviceAnnouncementListener`, `DeviceUpdateListener`(CdjStatus のみ処理、MixerStatus は無視)、`BeatListener`, `MasterListener`, `TrackMetadataListener`, `AlbumArtListener`, `WaveformListener`, `BeatGridListener`, `TrackPositionListener` を player ごとに `TimeFinder.addTrackPositionListener(n, ...)` で登録)

起動順と再試行(設計書 4.1): `DeviceFinder.start()` → `VirtualCdj.setDeviceName`, `setUseStandardPlayerNumber(config.deviceNumber == null)`, `deviceNumber` 指定時は `setDeviceNumber` → `VirtualCdj.start()`(false なら 5 秒待って再試行、`online=false` のまま)→ 成功したら `MetadataFinder.setPassive(VirtualCdj.getDeviceNumber() > 4)` → `BeatFinder, MetadataFinder, ArtFinder, BeatGridFinder, WaveformFinder(setPreferredStyle(RGB), setFindDetails(true)), TimeFinder` を start → `store.online(true)`。`deviceLost` で `DeviceFinder.getCurrentDevices()` が空なら `VirtualCdj.stop()`(他 Finder は VirtualCdj の lifecycle に追従して止まる)→ `store.online(false)` → 再試行ループへ。

- [ ] **Step 1: テストを書く** — `ConfigTest.fromEnvDefaults`(空 map → port 17080、deviceNumber null)、`ConfigTest.fromEnvParsesNumber`(`DEVICE_NUMBER=4` → 4)、`ListenersTest.beatToEvent`(`BeatEvent` 生成関数 `fun beatEventOf(b: Beat, positionMs: Long?, waveform: Pair<Int,String>?, now:Long): BeatEvent` に対し、`Beat` は実パケットが要るので Task 8 の `Packets.beat(...)` の出力から `Beat(DatagramPacket)` を作って渡す。`tempo == 128.0`, `beatWithinBar == 1`, `nextBeatInMs == 468`)
- [ ] **Step 2: Run** `./gradlew :server:test --tests 'cdjmusiclink.server.link.*'` Expected: FAIL
- [ ] **Step 3: 実装**(`LinkSession` 自体のテストは統合に回す)
- [ ] **Step 4: Run** 同コマンド Expected: PASS
- [ ] **Step 5: Commit** `feat(server): beat-link session with retry and listeners`

---

### Task 6: HTTP ルート(GET 系)と OpenAPI 契約テスト

**Files:**
- Create: `server/src/main/kotlin/cdjmusiclink/server/http/{Routes.kt, Docs.kt, Errors.kt}`, `Main.kt` を本実装に
- Test: `server/src/test/kotlin/cdjmusiclink/server/http/{RoutesTest.kt, WaveformRoutesTest.kt, ContractTest.kt}`

**Interfaces:**
- Consumes: `Store`, `Conversions`, Task 1 の `openapi.yaml`(`server/src/main/resources/openapi.yaml` へビルド時コピー)
- Produces: `fun Application.apiModule(store: Store, session: SessionInfo, assets: AssetSource)`。`SessionInfo { val online:Boolean; val deviceNumber:Int?; val version:String }`、`interface AssetSource { fun artwork(n:Int): ByteArray?; fun preview(n:Int): WaveformPreview?; fun detail(n:Int): WaveformDetail?; fun beatGrid(n:Int): BeatGrid? }`(本番は beat-link の Finder、テストはフィクスチャ)

- [ ] **Step 1: テストを書く**(`testApplication` + フィクスチャ `AssetSource` + 手で埋めた `Store`) — `RoutesTest.healthReportsOnline`、`RoutesTest.playersListsOnlyPlayers`(mixer を `upsertDevice` しても `/v1/players` に出ない)、`RoutesTest.unknownPlayerIs404WithErrorJson`、`RoutesTest.trackIs404WhenNoTrack`、`RoutesTest.artworkContentTypeFromBytes`(jpg フィクスチャ → `image/jpeg`)、`WaveformRoutesTest.previewMatchesFixture`(`segments == 1200`)、`WaveformRoutesTest.detailRejectsBadRange`(`?fromMs=5000&toMs=1000` → 400 かつ `error` 非空、`?fromMs=abc` → 400)、`RoutesTest.corsAllowsAnyOrigin`(`Origin: http://x` → `Access-Control-Allow-Origin: *`)、`ContractTest.allGetResponsesMatchSpec`(swagger-request-validator の `OpenApiInteractionValidator.createFor("openapi.yaml")` で `/v1/health, /v1/devices, /v1/players, /v1/players/1, /v1/players/1/track, /v1/players/1/waveform/preview, /v1/players/1/waveform/detail, /v1/players/1/beatgrid, /v1/master, /v1/now-playing` のレスポンスを検証し、違反 0)
- [ ] **Step 2: Run** `./gradlew :server:test --tests 'cdjmusiclink.server.http.*'` Expected: FAIL
- [ ] **Step 3: 実装**。`/docs` は webjar の swagger-ui を `/openapi.yaml` に向ける HTML を返す。`now-playing` は Task 4 の `Store` から `NowPlaying.pick(players, mixerPresent)` を呼ぶ(`state/NowPlaying.kt` をここで作る。規則は設計書 4.2)。`NowPlayingTest.prefersOnAirMasterWhenMixerPresent` と `NowPlayingTest.fallsBackToMasterWithoutMixer` を同タスクで追加。
- [ ] **Step 4: Run** 同コマンド Expected: PASS
- [ ] **Step 5: Commit** `feat(server): v1 http routes, swagger ui and contract tests`

---

### Task 7: SSE ストリーム

**Files:**
- Create: `server/src/main/kotlin/cdjmusiclink/server/http/Sse.kt`
- Test: `server/src/test/kotlin/cdjmusiclink/server/http/SseTest.kt`

**Interfaces:**
- Consumes: `EventBus.events`, `Store`
- Produces: `fun Route.streamRoute(store: Store, bus: EventBus)` が `/v1/stream` を `install(SSE)` の `sse {}` で出す。`event:` 名は `snapshot, beat, player, track, device, master, online`、`id:` は `Event.id`、`retry: 2000`。接続直後に `snapshot`(`{players, master, devices, online, serverTime}`)。

- [ ] **Step 1: テストを書く** — `SseTest.firstEventIsSnapshot`(`ktor-client` で接続 → 最初の `event: snapshot`)、`SseTest.publishedBeatArrivesWithEventName`(`bus.publish(BeatEvent(...))` → `event: beat` と `data` が `SseBeat` スキーマに合う(ContractTest と同じ validator で `components.schemas.SseBeat` を検証))、`SseTest.retryHeaderIs2000`
- [ ] **Step 2: Run** `./gradlew :server:test --tests 'cdjmusiclink.server.http.SseTest'` Expected: FAIL
- [ ] **Step 3: 実装**
- [ ] **Step 4: Run** 同コマンド Expected: PASS
- [ ] **Step 5: Commit** `feat(server): sse stream`

---

### Task 8: fake-cdj パケット生成

**Files:**
- Create: `fake-cdj/src/main/kotlin/cdjmusiclink/fakecdj/Packets.kt`
- Test: `fake-cdj/src/test/kotlin/cdjmusiclink/fakecdj/PacketsTest.kt`(beat-link でパース)

**Interfaces:**
- Produces: `object Packets` に
  - `fun keepAlive(name:String, number:Int, mac:ByteArray, ip:ByteArray): ByteArray`(54 B)
  - `fun status(p: StatusParams): ByteArray`(284 B)。`data class StatusParams(name:String, number:Int, playing:Boolean, master:Boolean, synced:Boolean, onAir:Boolean, bpmX100:Int, pitch:Int=0x100000, beatNumber:Int, beatWithinBar:Int, trackId:Int, trackLoaded:Boolean, slot:Int, trackType:Int, packetCounter:Int, syncCounter:Int)`
  - `fun beat(name:String, number:Int, bpmX100:Int, pitch:Int, beatWithinBar:Int, beatIntervalMs:Int, nextBarInMs:Int): ByteArray`(96 B)

バイト配置(絶対オフセット。テンプレートは beat-link `VirtualCdj.java` の `keepAliveBytes` / `STATUS_PAYLOAD` / `BEAT_PAYLOAD` をそのまま写す。出典を `NOTICE` に記す):

| パケット | オフセット | 内容 |
|---|---|---|
| 共通 | 0x00-0x09 | magic `Qspt1WmJOL` |
| keep-alive | 0x0a | 0x06 |
| keep-alive | 0x0c-0x1f | 名前(ASCII、20 B、0 埋め) |
| keep-alive | 0x24 | device 番号 |
| keep-alive | 0x25 | 0x01(CDJ) |
| keep-alive | 0x26-0x2b | MAC |
| keep-alive | 0x2c-0x2f | IPv4 |
| status/beat | 0x0a | 0x0a / 0x28 |
| status/beat | 0x0b-0x1e | 名前(20 B) |
| status | 0x21, 0x24 | device 番号 |
| status | 0x27 | playing ? 1 : 0 |
| status | 0x28 | 曲のソースプレイヤー(自分の番号) |
| status | 0x29 | スロット(0 none, 1 cd, 2 sd, 3 usb, 4 collection) |
| status | 0x2a | 曲種別(0 none, 1 rekordbox, 2 unanalyzed, 5 cd) |
| status | 0x2c-0x2f | rekordbox id(BE u32) |
| status | 0x7b | P1: playing ? 3 : 5 |
| status | 0x84-0x87 | sync counter(BE u32) |
| status | 0x89 | F: 0x84 + (playing 0x40) + (master 0x20) + (synced 0x10) + (onAir 0x08) |
| status | 0x8b | P2: playing ? 0x7a : 0x7e |
| status | 0x8c-0x8f, 0x98-0x9b | pitch(BE u32、0x100000 = 0%) |
| status | 0x92-0x93 | BPM×100(BE u16、未ロードなら 0xffff) |
| status | 0x9d | P3: playing ? 9 : 1 |
| status | 0x9e | Mm: master ? 1 : 0 |
| status | 0x9f | Mh: 0xff |
| status | 0xa0-0xa3 | 拍番号(BE u32) |
| status | 0xa6 | 小節内拍 1〜4 |
| status | 0xc8-0xcb | パケット連番(BE u32) |
| beat | 0x21, 0x5f | device 番号 |
| beat | 0x24, 0x28, 0x2c, 0x30, 0x34, 0x38 | 次拍, 2 拍後, 次小節, 4 拍後, 2 小節後, 8 拍後までの ms(BE u32) |
| beat | 0x54-0x57 | pitch |
| beat | 0x5a-0x5b | BPM×100 |
| beat | 0x5c | 小節内拍 |

- [ ] **Step 1: テストを書く**(beat-link の `DeviceAnnouncement(DatagramPacket)`, `CdjStatus(DatagramPacket)`, `Beat(DatagramPacket)` でパース) — `keepAliveParsesNameAndNumber`(名前 "fake-cdj-1"、番号 1、`getAddress` は DatagramPacket に与えた IP)、`statusParsesPlayingFlagsAndTrack`(playing/master/onAir true、bpmX100 12800、beatNumber 100、beatWithinBar 3、trackId 42、slot 3、type 1 → `isPlaying, isTempoMaster, isOnAir`、`getBpm()==12800`、`getBeatNumber()==100`、`getBeatWithinBar()==3`、`getRekordboxId()==42`、`getTrackSourceSlot()==USB_SLOT`、`getTrackType()==REKORDBOX`、`getEffectiveTempo()==128.0`)、`statusPausedHasNoPlayingFlag`、`beatParses`(bpmX100 12800、interval 468、nextBar 1404 → `getBpm()==12800`、`getNextBeat()==468`、`getNextBar()==1404`、`getBeatWithinBar()==1`、`getEffectiveTempo()==128.0`)、`packetLengths`(54 / 284 / 96)
- [ ] **Step 2: Run** `./gradlew :fake-cdj:test` Expected: FAIL
- [ ] **Step 3: `Packets.kt` を実装**
- [ ] **Step 4: Run** 同コマンド Expected: PASS
- [ ] **Step 5: Commit** `feat(fake-cdj): keep-alive, status and beat packet builders`

---

### Task 9: fake-cdj 実行部と制御 HTTP

**Files:**
- Create: `fake-cdj/src/main/kotlin/cdjmusiclink/fakecdj/{Deck.kt, Peers.kt, FakeCdj.kt, Control.kt, Main.kt}`
- Test: `fake-cdj/src/test/kotlin/cdjmusiclink/fakecdj/DeckTest.kt`

**Interfaces:**
- Consumes: `Packets`
- Produces:
  - `class Deck(bpm:Double, trackId:Int, slot:Int, trackType:Int)` に `var playing`, `var master`, `var synced`, `var onAir`, `var bpm`, `fun advance(nowMs:Long)`(再生中なら経過時間で拍番号を進める), `val beatNumber:Int`, `val beatWithinBar:Int`, `fun msUntilNextBeat(nowMs:Long):Int`, `fun load(trackId:Int, slot:Int, trackType:Int)`, `fun unload()`
  - `class Peers(bind: InetAddress)`: 50000 を listen し、自分以外の送信元 IP を `fun addresses(): Set<InetAddress>` で返す(10 秒無通信で除外)
  - `class FakeCdj(config: FakeConfig, deck: Deck, peers: Peers)` に `fun start()`/`fun stop()`。ループ: keep-alive 1.5 s broadcast(インタフェースのブロードキャストアドレス宛 50000)、status 200 ms で `peers.addresses()` 各宛て 50002、再生中は拍タイミングで beat を broadcast 50001。
  - `FakeConfig(number:Int, name:String, bpm:Double, trackId:Int, slot:Int, trackType:Int, autoplay:Boolean, iface:String?, controlPort:Int=17090)` を環境変数 `FAKE_NUMBER, FAKE_NAME, FAKE_BPM, FAKE_TRACK_ID, FAKE_SLOT, FAKE_TRACK_TYPE, FAKE_AUTOPLAY, FAKE_IFACE, FAKE_CONTROL_PORT` から
  - 制御 HTTP(JDK `com.sun.net.httpserver` で十分): `POST /play`, `POST /pause`, `POST /tempo {"bpm":130.0}`, `POST /load {"trackId":7,"slot":3,"trackType":1}`, `POST /unload`, `POST /master {"master":true}`, `POST /onair {"onAir":true}`, `GET /state`

判断: 制御 HTTP のポートは 17090。MAC は実インタフェースの `NetworkInterface.getHardwareAddress()`、取れなければ `02:00:00:00:00:<number>`。

- [ ] **Step 1: `DeckTest` を書く** — `advanceCountsBeatsAt128`(128 BPM で 4680 ms 進める → `beatNumber == 10`、`beatWithinBar == 2`)、`pausedDoesNotAdvance`、`msUntilNextBeatIsPositiveAndBelowInterval`
- [ ] **Step 2: Run** `./gradlew :fake-cdj:test --tests '*DeckTest'` Expected: FAIL
- [ ] **Step 3: 実装**(`Deck` → `Peers` → `FakeCdj` → `Control` → `Main`)
- [ ] **Step 4: Run** `./gradlew :fake-cdj:test` Expected: PASS
- [ ] **Step 5: ローカル手動確認** — Run: 端末 1 で `FAKE_NUMBER=1 ./gradlew :fake-cdj:run`、端末 2 で `sudo tcpdump -i lo0 -n udp port 50000 -c 2` Expected: 54 バイトの UDP が 1.5 秒間隔で見える(macOS の lo0 で broadcast が見えない場合はこのステップを Task 10 の compose で代替)
- [ ] **Step 6: Commit** `feat(fake-cdj): deck simulation, announce/status/beat loops, control http`

---

### Task 10: Docker と模擬 compose、統合テスト

**Files:**
- Create: `server/Dockerfile`, `fake-cdj/Dockerfile`, `deploy/compose.emu.yaml`, `scripts/emu-test.sh`, `scripts/smoke.sh`

**Interfaces:**
- Consumes: Task 5〜9 の成果物
- Produces: `docker compose -f deploy/compose.emu.yaml up` で `fake-cdj-1`(番号 1、128 BPM、autoplay、trackId 101)、`fake-cdj-2`(番号 2、停止、trackId 202)、`server`(host に 17080 を publish、`fake-cdj-*` の制御ポートも 17091/17092 に publish)が bridge `djlink` で動く。

判断: Dockerfile は multi-stage(`gradle:9.7.1-jdk25` でビルド → `eclipse-temurin:25-jre`。どちらも arm64 あり)。`server` は `JAVA_TOOL_OPTIONS=-Xmx512m`。`fake-cdj` は `cap_add` 不要(50000〜50002 は非特権ポート)。

- [ ] **Step 1: Docker Desktop を起動し、bridge の broadcast を確認** — Run: `docker network create bctest && docker run -d --rm --name rx --network bctest alpine/socat -u UDP-RECV:50000 STDOUT && sleep 1 && docker run --rm --network bctest alpine/socat -u STDIN UDP-DATAGRAM:255.255.255.255:50000,broadcast <<< hello && sleep 1 && docker logs rx; docker rm -f rx; docker network rm bctest` Expected: `hello` が rx のログに出る。出なければ、以降の統合テストは Linux(Pi か CI)で回すと README に書き、このタスクはビルドまでで止める。
- [ ] **Step 2: Dockerfile 2 本と `compose.emu.yaml` を書く**
- [ ] **Step 3: `scripts/emu-test.sh` を書く**(bash + curl + jq)。手順: `compose up -d --build` → `/v1/health` が `online: true` になるまで最大 90 秒待つ → `/v1/players | jq length` が 2 → `/v1/players/1 | jq .playState` が `"playing"` かつ `.tempo` が 127.9〜128.1 → `/v1/players/1 | jq .track.id` が 101 → `curl -N --max-time 3 /v1/stream` に `event: beat` が 4 回以上 → `POST localhost:17092/play` 後 `/v1/players/2 | jq .isPlaying` が true → `compose stop fake-cdj-1 fake-cdj-2` 後 20 秒以内に `online: false` → `compose start` 後 90 秒以内に `online: true` かつ players が 2 → `compose down`。各段階で失敗したら非 0 終了。
- [ ] **Step 4: Run** `scripts/emu-test.sh` Expected: 全段階 OK、終了コード 0
- [ ] **Step 5: `scripts/smoke.sh`**(全 GET を curl して status を表示するだけ)を書き、`BASE=http://localhost:17080 scripts/smoke.sh` で全 200/404 が想定通り
- [ ] **Step 6: Commit** `feat: dockerfiles, emulation compose and integration script`

---

### Task 11: Pi 向け compose、CI、ドキュメント

**Files:**
- Create: `deploy/compose.yaml`, `deploy/pi-setup.md`, `.github/workflows/ci.yml`, `README.md`, `docs/hardware-checklist.md`

**Interfaces:**
- Consumes: Task 10 のイメージ

判断: イメージ名は `ghcr.io/silmin/cdj-music-link-server` と `ghcr.io/silmin/cdj-music-link-fake-cdj`。CI は push と PR で `./gradlew test`、`main` への push で buildx により `linux/arm64,linux/amd64` を GHCR に push し、その後 Linux ランナーで `scripts/emu-test.sh` を実行。

- [ ] **Step 1: `deploy/compose.yaml`** — `server` のみ、`image: ghcr.io/silmin/cdj-music-link-server:latest`、`network_mode: host`、`restart: unless-stopped`、`environment: PORT, DEVICE_NAME, DEVICE_NUMBER, LOG_LEVEL` を `.env` から。
- [ ] **Step 2: `deploy/pi-setup.md`** — Raspberry Pi OS 64bit、Docker Engine 導入、eth0 の NetworkManager 設定(`ipv4.method auto` + `ipv4.link-local enabled`、DHCP 失敗で 169.254 に落ちる)、wlan0 の固定 IP、avahi で `cdj-music-link.local`、`docker compose -f deploy/compose.yaml up -d`、確認コマンド(`curl http://localhost:17080/v1/health`)。
- [ ] **Step 3: `.github/workflows/ci.yml`**(test ジョブ、build-push ジョブ、emu-test ジョブ)
- [ ] **Step 4: `README.md`** — 何をするもの、API の一覧と curl 例、SSE の例、制約(PC DJ は見えない、NXS2 は位置が補間値、USB 前提)、模擬環境の動かし方、Pi への配備リンク。
- [ ] **Step 5: `docs/hardware-checklist.md`** — 設計書 5 節「実機」の項目をチェックリスト化。
- [ ] **Step 6: Run** `./gradlew test && npx -y @redocly/cli@1 lint openapi/openapi.yaml` Expected: 全 PASS
- [ ] **Step 7: Commit** `docs: pi deployment, ci workflow, readme and hardware checklist`
