# cdj-tui

cdj-music-link の HTTP API だけを見る端末 UI。サーバが模擬環境でも Raspberry Pi でも、同じように使える。

## ビルドと起動

```sh
cd tui && go build ./cmd/cdj-tui && ./cdj-tui --base http://<pi>:17080
# ビルドせずに
cd tui && go run ./cmd/cdj-tui
```

- `--base URL`: サーバの URL。既定は環境変数 `CDJ_BASE`、なければ `http://localhost:17080`。
- 起動時にスナップショットを取れなければエラーを出して終了する。
- 最小サイズは 80×24。小さいと「ターミナルを広げてください」を表示する。UTF-8 端末でブロック・罫線文字が出ることが前提。

## キー

| キー | 動作 |
|---|---|
| `q` | 終了 |
| `b` | 拍の点滅(`beat ●○○○`)の on/off |
| `w` | デッキの波形行の on/off |

## 画面の読み方

- ヘッダ: `●online` / `○offline`、自分のプレイヤー番号、見えているデバイス、時刻。
- NOW PLAYING: `/v1/now-playing` が選んだデッキの曲名・アーティスト・キー・BPM、進行バー(経過 / 全長)、`bar N  beat ●○○○`(今の拍を ● で示す)。何も再生していなければ `(nothing playing)`。
- デッキ: `/v1/players` の 1 台が 1 箱。状態、MASTER / ON AIR、曲、`経過 / 全長  pitch`、波形行。1 行に `max(1, 幅/40)` 個並べ、入りきらない分はフッタに `+N decks hidden` と出る。
- イベントログ: `HH:MM:SS` 付きで古い順に並び、最新が下。`link:` 始まりは接続状態。
- フッタ: `q quit  b blink  w wave  stream:live|reconnecting`。

## 更新の仕組み

- 起動時に `/v1/players` などのスナップショットを取り、その後は `/v1/stream`(SSE)のイベントで状態を更新する。
- ストリームが切れたら 2 秒後に再接続し、新しいスナップショットを取り直す(その間フッタは `reconnecting`)。
- 再生位置は 30 ms 位置パケットがないため、拍ごとの位置から間を補間して表示する。
- 死んだ相手の検出: TCP keep-alive を 15 秒ごとに送る。さらに、再生中のデッキがあるのに 90 秒イベントが来なければストリームが死んだとみなして再接続する。

## 模擬環境で試す

```sh
# ターミナル 1(シナリオを有効のまま)
scripts/emu-watch.sh
# ターミナル 2
cd tui && go run ./cmd/cdj-tui
```

## 注意

- メディアサーバ(模擬 USB)は `empty` のデッキとして現れる。
- `fake-cdj` はどの曲も 128 BPM で再生するため、ログの `tempo` は曲自身の BPM と一致しない。
