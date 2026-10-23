package cdjmusiclink.server.link

import cdjmusiclink.server.http.AssetSource
import org.deepsymmetry.beatlink.data.ArtFinder
import org.deepsymmetry.beatlink.data.BeatGrid
import org.deepsymmetry.beatlink.data.BeatGridFinder
import org.deepsymmetry.beatlink.data.WaveformDetail
import org.deepsymmetry.beatlink.data.WaveformFinder
import org.deepsymmetry.beatlink.data.WaveformPreview

/** beat-link の各 Finder が保持している最新データを返す。Finder が未起動なら null。 */
class LinkAssetSource : AssetSource {
    override fun artwork(n: Int): ByteArray? {
        val finder = ArtFinder.getInstance()
        if (!finder.isRunning) return null
        val buf = finder.getLatestArtFor(n)?.rawBytes ?: return null
        val copy = buf.asReadOnlyBuffer()
        return ByteArray(copy.remaining()).also { copy.get(it) }
    }

    override fun preview(n: Int): WaveformPreview? =
        WaveformFinder.getInstance().takeIf { it.isRunning }?.getLatestPreviewFor(n)

    override fun detail(n: Int): WaveformDetail? =
        WaveformFinder.getInstance().takeIf { it.isRunning }?.getLatestDetailFor(n)

    override fun beatGrid(n: Int): BeatGrid? =
        BeatGridFinder.getInstance().takeIf { it.isRunning }?.getLatestBeatGridFor(n)
}
