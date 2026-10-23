package cdjmusiclink.server.http

import cdjmusiclink.server.model.Device
import cdjmusiclink.server.model.DeviceKind
import cdjmusiclink.server.state.EventBus
import cdjmusiclink.server.state.Store
import cdjmusiclink.server.state.snap
import java.nio.ByteBuffer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.deepsymmetry.beatlink.CdjStatus
import org.deepsymmetry.beatlink.data.BeatGrid
import org.deepsymmetry.beatlink.data.DataReference
import org.deepsymmetry.beatlink.data.WaveformDetail
import org.deepsymmetry.beatlink.data.WaveformFinder.WaveformStyle
import org.deepsymmetry.beatlink.data.WaveformPreview

@Serializable
private class BeatGridFixture(val beatWithinBar: List<Int>, val timeMs: List<Long>, val bpm: List<Int>)

fun fixture(name: String): ByteArray =
    checkNotNull(object {}.javaClass.getResourceAsStream("/fixtures/sample1/$name")) { "missing $name" }.use { it.readBytes() }

/** sample1 フィクスチャを返す AssetSource。プレイヤー 1 だけが持つ。 */
class FixtureAssets : AssetSource {
    private val ref = DataReference(1, CdjStatus.TrackSourceSlot.USB_SLOT, 1)
    override fun artwork(n: Int): ByteArray? = if (n == 1) fixture("art.jpg") else null
    override fun preview(n: Int): WaveformPreview? =
        if (n == 1) WaveformPreview(ref, ByteBuffer.wrap(fixture("preview-color.data")), WaveformStyle.RGB) else null
    override fun detail(n: Int): WaveformDetail? =
        if (n == 1) WaveformDetail(ref, ByteBuffer.wrap(fixture("detail-color.data")), WaveformStyle.RGB) else null
    override fun beatGrid(n: Int): BeatGrid? {
        if (n != 1) return null
        val f = Json.decodeFromString<BeatGridFixture>(String(fixture("beat-grid.json")))
        return BeatGrid(ref, f.beatWithinBar.toIntArray(), f.bpm.toIntArray(), f.timeMs.toLongArray())
    }
}

class FakeSession(override val online: Boolean = true, override val deviceNumber: Int? = 5, override val version: String = "9.9.9") : SessionInfo

/** CDJ 1 台(曲ロード済み)とミキサーを持つ Store。 */
fun filledStore(withMixer: Boolean = true): Store {
    val store = Store(EventBus()) { 1_700_000_000_000 }
    store.upsertDevice(Device(1, "CDJ-3000", "10.0.0.1", DeviceKind.player))
    if (withMixer) store.upsertDevice(Device(33, "DJM-V10", "10.0.0.9", DeviceKind.mixer))
    store.status(1, snap(master = true))
    store.master(1, 128.0)
    return store
}
