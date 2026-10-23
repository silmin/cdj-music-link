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
