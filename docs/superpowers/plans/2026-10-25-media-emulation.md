# Media emulation (prolink serve) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task.

**Goal:** 模擬環境に Rust 製 `prolink serve` を足し、rekordbox で書き出した実 USB のデータベースと解析ファイルを NFS/dbserver で配らせることで、server の曲名・波形・ビートグリッド・位置補間の経路を実機なしで通す。

**Architecture:** compose の `media` プロファイルに `media` サービス(prolink-cli 0.5.0 を crates.io からビルド、device 4、`--usb /media`)を追加。ホストの USB(`.PIONEER` と `Contents`)を `/media/PIONEER`、`/media/Contents` に読み取り専用 bind mount。偽 CDJ は「track の出所 = device 4」をステータスに書けるよう `FAKE_SOURCE_PLAYER` を得る。emu-test は `EMU_MEDIA=1` のとき追加段階を検証する。

**Tech Stack:** 既存 + `rust:1.99-slim-bookworm`(ビルド)/ `debian:bookworm-slim`(実行)、`cargo install prolink-cli --version 0.5.0`(MSRV 1.97)。

**Spec:** docs/superpowers/specs/2026-10-24-cdj-music-link-design.md 4.4〜4.5、非ゴールの「prolink serve」を v1.1 で解除。

## Global Constraints
- server / fake-cdj の既存テスト(82 件)と、`EMU_MEDIA` なしの `scripts/emu-test.sh` は変更後も通る。
- USB の中身をリポジトリにコミットしない。prolink イメージ(GPL-3.0)は CI で push しない。
- 番号: fake-cdj-1 = 1, fake-cdj-2 = 2, server = 自動(3), media = 4。
- `PIONEER/rekordbox/export.pdb` の配置は bind mount で作る(ホストでは `.PIONEER`)。
- fake-cdj は beat-link に実行時依存しない(変わらず)。

## Review Focus
1. `FAKE_SOURCE_PLAYER` 未指定時は従来どおり自分の番号(既存 PacketsTest が通る)。Task A のテストで固定。
2. media プロファイル無しで `docker compose -f deploy/compose.emu.yaml up` が従来どおり 3 サービスで動く。Task B で `compose config` を比較。
3. 実在しない track id を偽 CDJ に持たせたとき server が落ちず `track.title` が null のまま(metadata が来ないだけ)。Task B の統合で確認(id を 999999 にした negative check を 1 回)。

---

### Task A: fake-cdj に track の出所プレイヤーを持たせる
**Files:** Modify `fake-cdj/src/main/kotlin/cdjmusiclink/fakecdj/{Packets.kt, Deck.kt, FakeCdj.kt, Control.kt, FakeConfig.kt}`; Test `fake-cdj/src/test/kotlin/cdjmusiclink/fakecdj/{PacketsTest.kt, DeckTest.kt}`
**Interfaces:** `StatusParams` に `sourcePlayer: Int = number`(status 0x28 に書く。trackLoaded=false なら 0 のまま)。`Deck.load(trackId, slot, trackType, sourcePlayer = number)`、`Deck.sourcePlayer`。`FakeConfig.sourcePlayer: Int?`(env `FAKE_SOURCE_PLAYER`、未指定なら番号)。`POST /load` body に任意の `sourcePlayer`。`GET /state` に `sourcePlayer`。
- [ ] テスト: `PacketsTest.statusCarriesSourcePlayer`(sourcePlayer=4 → `CdjStatus.getTrackSourcePlayer()==4`)、`PacketsTest.statusSourcePlayerDefaultsToOwnNumber`、`DeckTest.loadSetsSourcePlayer`
- [ ] 実装、`./gradlew test`、commit `feat(fake-cdj): configurable track source player`

### Task B: media サービス、compose プロファイル、emu-test の拡張
**Files:** Create `deploy/media/Dockerfile`, `deploy/media/README.md`; Modify `deploy/compose.emu.yaml`, `scripts/emu-test.sh`, `README.md`, `docs/hardware-checklist.md`(模擬で確認できた項目に印)
**Interfaces:** compose サービス `media`(`profiles: [media]`, image `cdj-music-link-media:dev`, `networks: [djlink]`, volumes `${MEDIA_ROOT:-/Volumes/nordblue2}/.PIONEER:/media/PIONEER:ro`, `${MEDIA_ROOT:-/Volumes/nordblue2}/Contents:/media/Contents:ro`, command `serve --usb /media --number 4 --interface eth0 -v`)。fake-cdj-1 は `FAKE_SOURCE_PLAYER=${MEDIA_DEVICE:-}`(空なら従来)と `FAKE_TRACK_ID=${MEDIA_TRACK_ID:-101}`。`scripts/emu-test.sh` は `EMU_MEDIA=1` のとき `--profile media` で up し、追加段階: `track.title` 非 null(≤60 s)、`/v1/players/1/waveform/preview` 200 かつ `segments==1200`、`/v1/players/1/beatgrid` 200 かつ `beats` 非空、`/v1/players/1` の `position.ms` が増える(2 回取得で増加)、negative: `POST :17091/load {"trackId":999999,"slot":3,"trackType":1,"sourcePlayer":4}` 後 10 s で `track.title==null` かつ `/v1/health` が online のまま。
- [ ] Dockerfile(multi-stage: `rust:1.99-slim-bookworm` で `cargo install prolink-cli --version 0.5.0 --locked`、runtime `debian:bookworm-slim` + ca-certificates、`ENTRYPOINT ["prolink"]`)
- [ ] 実在 id の取得: ビルドしたイメージで `prolink tracks /media/PIONEER/rekordbox/export.pdb | head` を実行し、先頭の id を `MEDIA_TRACK_ID` の既定値として `deploy/media/README.md` に記録(compose の既定は 101 のまま、`.env` 例に書く)
- [ ] compose プロファイル、emu-test 拡張、`EMU_MEDIA=1 MEDIA_DEVICE=4 MEDIA_TRACK_ID=<id> scripts/emu-test.sh` を通す。通らない場合は `docker compose logs media server` を報告に貼る(beat-link の NFS/dbserver 側の問題なら BLOCKED)
- [ ] `EMU_MEDIA` なしの `scripts/emu-test.sh` も再度通す
- [ ] README(模擬で曲名まで通す手順、USB の `.PIONEER` 注意、GPL-3.0 の注記)、hardware-checklist の更新、commit
