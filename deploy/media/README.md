# media: 模擬 rekordbox USB(prolink serve)

`deploy/compose.emu.yaml` の `media` プロファイルで動く、rekordbox で書き出した USB を DJ Link に出す仮想プレイヤー。中身は [prolink](https://github.com/usr-ein/prolink) の `prolink serve`(crate `prolink-cli` 0.5.0)。device 4 として名乗り、USB スロットに手元の USB の中身を載せる。`fake-cdj-1` がその曲を「device 4 の USB から読んだ」と報告するので、server の beat-link が device 4 からメタデータ・アートワーク・波形・ビートグリッドを取る。

## ライセンス

prolink は GPL-3.0-only。このイメージ(`cdj-music-link-media:dev`)は手元の模擬環境専用で、CI の `build-push` には載せない。リポジトリには Dockerfile だけを置き、prolink のソースやバイナリ、USB の中身は入れない。

## USB の `.PIONEER` について

rekordbox は USB 直下に隠しフォルダ `.PIONEER/` を作る(`rekordbox/export.pdb`、`USBANLZ/`、`Artwork/`)。音声は `Contents/`。prolink は `PIONEER/rekordbox/export.pdb` で媒体を見つけるが、`export.pdb` に書かれた解析ファイルのパスは `/.PIONEER/USBANLZ/...` になっている。そのため compose は `.PIONEER` を `/media/PIONEER` と `/media/.PIONEER` の両方に、`Contents` を `/media/Contents` に読み取り専用でマウントする。片方だけだと曲名は出るが、波形とビートグリッドが空になる。

## 使い方

```sh
docker build -t cdj-music-link-media:dev deploy/media     # compose up --build でも作られる

# 曲 id を探す(オフライン。先頭が rekordbox の id)
docker run --rm -v /Volumes/nordblue2/.PIONEER:/media/PIONEER:ro \
  cdj-music-link-media:dev tracks /media/PIONEER/rekordbox/export.pdb | head

cp deploy/media/.env.example deploy/media/.env               # MEDIA_TRACK_ID などを編集
docker compose -f deploy/compose.emu.yaml --env-file deploy/media/.env --profile media up --build
curl -s localhost:17080/v1/players/1 | jq .track.title

EMU_MEDIA=1 MEDIA_TRACK_ID=308 scripts/emu-test.sh          # 結合テスト
```

| 変数 | 既定 | 意味 |
|---|---|---|
| `MEDIA_ROOT` | `/Volumes/nordblue2` | USB のマウント先(`.PIONEER/` と `Contents/` がある場所) |
| `MEDIA_DEVICE` | 空(`emu-test.sh` では 4) | `fake-cdj-1` が報告する曲の読み込み元。4 にすると media から読んだことになる。空なら従来どおり自分自身 |
| `MEDIA_TRACK_ID` | 空(`fake-cdj-1` は 101) | `fake-cdj-1` がロードしておく曲の rekordbox id。USB に実在する id を指定する |

`deploy/.env` には書かない。compose は `deploy/.env` を模擬環境の変数展開にも使うので、media なしの `scripts/emu-test.sh` が壊れる。

## 曲 id の例

例の `MEDIA_TRACK_ID=308` は、手元の USB で rekordbox が解析済みの曲の id。id は USB ごとに異なるので、`prolink tracks` の出力から自分の USB の id を選ぶ。

## 確認できたことと制約

- server は device 3 を取り、device 4 の dbserver(port 1051)からメタデータ・アートワーク・ビートグリッド・波形を取る。NFS 経由(Crate Digger)では取っていない。
- prolink 0.5.0 は beat-link のカラー波形要求(`get_analysis_tag`、PWV4/PWV5)に応答しない。beat-link はブルー波形に切り替えるので、`/waveform/preview` は `style: blue`、`segments: 450` になる。server のログに `Expected: ANLZ_TAG` の IOException が 1 曲につき 2 回出る。
- `media` も `/v1/players` に 1 台として出る(合計 3 台)。
- 起動順: `server` は `depends_on: media`(`required: false`、プロファイル無効時は無視される)で media の後にコンテナが起動する。prolink が device 4 を確保するのはコンテナ起動から約 10 秒後で、server の番号確保とどちらが先かは保証されない。ログを確認した実行では、いずれも server が 3、media が 4 になった。
- USB にない id をロードすると、prolink は空のメタデータを返す。server はそれを `track.title: null` として返し、`online` のまま。

## 動かし続けて眺める: `scripts/emu-watch.sh`

模擬環境を起動したまま、server の SSE をイベントごとに 1 行で表示する(`HH:MM:SS` 付き)。既定では USB の実在曲 id を `prolink tracks` で拾い、`fake-cdj-1` / `fake-cdj-2` に交互にロード・再生する DJ 風シナリオも流す(`scenario:` 始まりの行がシナリオ側の操作)。

```sh
scripts/emu-watch.sh                 # media 込み(deploy/media/.env があれば自動)。Ctrl-C で compose down して終了
scripts/emu-watch.sh --beats         # 小節頭のビートも表示
scripts/emu-watch.sh --no-scenario   # 観測だけ
scripts/emu-watch.sh --keep          # 終了時に down しない
```

表示は `link online`、`device 4 connected …`、`player 1  playing  128.0 BPM  master`、`player 1  track loaded  id=308 …`、`player 1  metadata  "曲名" — アーティスト …`、`tempo master → player 1 …`。加えて `GET /v1/now-playing` を player/track/master イベントの後(最大 300 ms に 1 回)に引き、結果が変わったときだけ `now playing → player 1  "曲名" — アーティスト`(該当なしは `now playing → none`)を出す。シナリオは USB の全曲 id を毎回シャッフルして順に流す(`EMU_SHUFFLE=0` で export 順)。環境変数: `EMU_MEDIA`(0 で media なし)、`EMU_SWITCH_SEC`(既定 45)、`EMU_OVERLAP_SEC`(既定 12)、`EMU_SHUFFLE`(既定 1)、`MEDIA_ROOT`、`MEDIA_DEVICE`、`MEDIA_TRACK_ID`、`BASE`。
