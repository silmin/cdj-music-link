# 既知の課題と後回しにした指摘

v1 実装時のタスク別レビューで Minor と判定され、マージ前には直さなかった項目。最終レビュー(2026-10-24)で「待てる」と判断されたもの。

- Task 0: no pluginManagement repositories block in settings.gradle.kts; toolchain/JUnit block duplicated in both build.gradle.kts
- Task 1: SseBeat nullable fields lack "null when" descriptions; no whole-object Player example; SsePlayer lacks type: object; consider redocly.yaml to silence no-unused-components for Sse* schemas
- Task 2: ApiJson object placed in Dto.kt (controller told the implementer to; could move to Json.kt); waveform style is String not enum; slotOf/trackTypeOf tests cover one value each; no nested Position/Track serialization test
- Task 3: deprecated 3-arg DataReference ctor in ConversionsTest emits a compile warning (use 4-arg); toTrack untested; columnAt toInt before coerceIn can wrap on absurd ms; detail toMs may overstate frames; durationSec/rating/year 0 not mapped to null (spec question, left as-is)
- Task 8: no tests for require rejections/name truncation/synced; tautological size checks; slot/trackType bytes unvalidated; hex()/decodeHex split redundant
- Task 4: slowSubscriber bound could be exact 45; idIsAssignedByBusAndNotSerialized has dead bus.publish lines; withId returns Event (cast); PlayerState name/address immutable if status precedes announce; applyMetadata(null) clears unconditionally and metadata overwrites availability flags; extras (Store.availability, isOnline) untested; publish-after-lock may reorder under contention
- Task 4: PlayerEventSerializer reuses Player's descriptor (serial name "Player"); polymorphic encoding of sealed Event would lack a discriminator — SSE layer must encode concrete event classes
- Task 5: movementChanged(null) NPEs inside guarded{} and logs WARN on every unload; stop()/start() in-process loses position listeners; stop() during blocking start() briefly flips online true; non-volatile job read in loop condition; masterChanged drops mixer tempo via CdjStatus cast; redundant device seeding
- Task 6: webjar allow-list accepts dot-only names (reject leading '.' or use extension allow-list); "null then 404" repeated in 5 handlers; Swagger UI version hardcoded in Docs.kt (drift vs catalog); Health uses wall clock not injected clock; player filter in route layer; webjar test checks only css
- Task 7: SseTest reads ../openapi/openapi.yaml relative to cwd (use classpath copy); beat schema check is key-only; JDK HttpClient/Stream not closed on timeout path; possible duplicate event between snapshot and stream (harmless); import order
- Task 9: Deck getters unsynchronized (prod reads via snapshot only); beat loop sleep not re-checked after /tempo or /load; pause/resume inside 50 ms window may skip a beat; load anchors at stale lastNowMs; POST /play without track returns 200; wall clock vs nanoTime; coverage gaps (setTempo phase, FakeConfig defaults); reply() in catch could rethrow; Iface data class with ByteArray
Task 10: review clean (deliverables); minor (deferred): Dockerfiles COPY . . before installDist (no dependency layer cache); final down only via trap (no ok: down line); compose has no restart/healthcheck; runtime image runs as root; curl/grep beat count message terse
- Task 5: drain wait depends on beat-link thread name "Lifecycle announcement delivery" (silent degradation on rename); isDown ignores Finder-level health; Thread.getAllStackTraces polling during teardown; staleStopSignal test name overstates; Store keeps last position after movementChanged(null)
- Task 11: redocly major-floating in CI; emu-test and build-push rebuild images without shared cache; README `open` is macOS-only and lacks response/SSE examples; README's "beat-link NPE fixed upstream, unreleased" claim unsourced (verify or soften); pi-setup states mixer channel-port numbering as fact (soften), assumes git present, no `nmcli connection show` for wlan0, no note that GHCR packages default to private (Pi pull needs public package or docker login)

## 最終再レビューで残った観察
- Conversions.toTrack は duration 0 を durationSec=0 のまま返す(remainingMs 側は null 化済み)
- アンロード直後に遅れて届いた位置更新が position を再設定しうる(非ブロッキング)

## CI の非推奨警告(2026-10-24 の初回 run)
- `actions/setup-java@v4` は非推奨(最新 v6)、`actions/checkout@v4` 等は Node 20 対象で Node 24 強制実行の警告。動作に影響なし。使っている action はいずれも 1〜3 メジャー遅れ(checkout v7、gradle/actions v6、docker 系 v4/v7)なので、入力名の変更を確認してからまとめて上げる

## 模擬環境(media プロファイル)で後回しにした点(2026-10-25)
- emu-test は `/v1/players/1/artwork` を検証していない(README の記述は「経路がある」の意味で、確認済みではない)
- prolink 0.5.0 はカラー波形の解析タグ要求に未対応のため、模擬では青いモノクロ波形(450 列)しか取れない。カラー波形(1200 列)は実機で確認
- server(自動で 3)と media(`--number 4`)の番号取得は原理的に競合しうる。観測した全回で server=3、media=4 だったが、`depends_on` は起動順の目安に過ぎない
- Conversions の `blankToNull` / `zeroToNull` が public の拡張関数(internal で足りる)。`toTrack` 本体は TrackMetadata を組めないため未テストのまま
- Task A: Control の `/state` と FakeCdj のパケットはまだ `config.number` を読む(Deck.number と常に一致)。sourcePlayer 省略時の `/load` を番号 1 以外のデッキで検証するテストがない

## cdj-tui(2026-10-25 の最終レビューで後回しにした点)
- (2026-10-28 解消)波形取得の失敗は 404 以外なら 5 s → 10 s → … 60 s の間隔で再試行する。位置補間は snapshot の `serverTime` から取った時計オフセットで補正する
- 80 列のデッキ枠では和文タイトルが 5 文字程度しか見えない(NOW PLAYING 側は長く出る)。狭い幅ではタイトルを独立行にする案
- 「再生中なのに 90 秒イベントなし」の再接続規則は、ビートグリッドのない曲(拍イベントが来ない)で約 90 秒ごとの無駄な再接続を起こしうる(未確認)
- 端末の「曖昧幅文字を全角で表示」設定では罫線やブロック文字がはみ出す可能性(未検証)
- `.gitignore` の `tui/cdj-tui` と `tui/.gitignore` の `/cdj-tui` が重複
