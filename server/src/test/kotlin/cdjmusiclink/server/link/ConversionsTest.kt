package cdjmusiclink.server.link

import java.awt.Color
import java.nio.ByteBuffer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.deepsymmetry.beatlink.CdjStatus
import org.deepsymmetry.beatlink.data.BeatGrid
import org.deepsymmetry.beatlink.data.DataReference
import org.deepsymmetry.beatlink.data.WaveformDetail
import org.deepsymmetry.beatlink.data.WaveformFinder.WaveformStyle
import org.deepsymmetry.beatlink.data.WaveformPreview
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@Serializable
private class BeatGridFixture(val beatWithinBar: List<Int>, val timeMs: List<Long>, val bpm: List<Int>)

class ConversionsTest {
    private val ref = DataReference(1, CdjStatus.TrackSourceSlot.USB_SLOT, 1)

    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/sample1/$name")) { "missing $name" }.use { it.readBytes() }

    private fun preview() = WaveformPreview(ref, ByteBuffer.wrap(resource("preview-color.data")), WaveformStyle.RGB)

    @Test
    fun previewToDtoHas1200ColorColumns() {
        val dto = preview().toDto()
        assertEquals("rgb", dto.style)
        assertEquals(1200, dto.segments)
        assertEquals(1200, dto.heights.size)
        assertEquals(1200, dto.colors.size)
        assertTrue(Regex("#[0-9a-f]{6}").matches(dto.colors[0]))
    }

    @Test
    fun detailToDtoRangeClips() {
        val detail = WaveformDetail(ref, ByteBuffer.wrap(resource("detail-color.data")), WaveformStyle.RGB)
        val dto = detail.toDto(1000, 2000)
        assertEquals(150, dto.framesPerSecond)
        assertEquals(1000L, dto.fromMs)
        assertEquals(2000L, dto.toMs)
        assertEquals(150, dto.heights.size)
        assertEquals(150, dto.colors.size)
    }

    @Test
    fun beatGridToDtoFirstBeat() {
        val f = Json.decodeFromString<BeatGridFixture>(String(resource("beat-grid.json")))
        val grid = BeatGrid(ref, f.beatWithinBar.toIntArray(), f.bpm.toIntArray(), f.timeMs.toLongArray())
        val dto = grid.toDto()
        assertEquals(f.beatWithinBar.size, dto.beats.size)
        assertEquals(1, dto.beats[0].number)
        assertEquals(1, dto.beats[0].beatWithinBar)
        assertEquals(f.timeMs[0], dto.beats[0].ms)
        assertEquals(f.bpm[0] / 100.0, dto.beats[0].bpm)
    }

    @Test
    fun columnAtMidTrack() {
        val p = preview()
        val total = 200_000L
        val (height, color) = p.columnAt(total / 2, total)
        assertTrue(height in 0..p.maxHeight)
        assertTrue(Regex("#[0-9a-f]{6}").matches(color))
        assertEquals(p.columnAt(0, 0), p.columnAt(0, total))
    }

    @Test
    fun colorHexFormatsRed() {
        assertEquals("#ff0000", colorHex(Color(255, 0, 0)))
    }

    @Test
    fun blankToNullMapsEmptyAndWhitespace() {
        assertEquals(null, null.blankToNull())
        assertEquals(null, "".blankToNull())
        assertEquals(null, "  \t\n".blankToNull())
        assertEquals("a", "a".blankToNull())
        assertEquals(" a ", " a ".blankToNull())
    }

    @Test
    fun zeroToNullMapsZeroOnly() {
        assertEquals(null, 0.zeroToNull())
        assertEquals(1, 1.zeroToNull())
        assertEquals(-1, (-1).zeroToNull())
    }
}
