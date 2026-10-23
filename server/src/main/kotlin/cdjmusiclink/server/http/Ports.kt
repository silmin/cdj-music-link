package cdjmusiclink.server.http

import org.deepsymmetry.beatlink.data.BeatGrid
import org.deepsymmetry.beatlink.data.WaveformDetail
import org.deepsymmetry.beatlink.data.WaveformPreview

/** 実行中セッションの状態。本番は LinkSession、テストは固定値。 */
interface SessionInfo {
    val online: Boolean
    val deviceNumber: Int?
    val version: String
}

/** 曲に紐づく重いデータの取得元。本番は beat-link の Finder、テストはフィクスチャ。 */
interface AssetSource {
    fun artwork(n: Int): ByteArray?
    fun preview(n: Int): WaveformPreview?
    fun detail(n: Int): WaveformDetail?
    fun beatGrid(n: Int): BeatGrid?
}
