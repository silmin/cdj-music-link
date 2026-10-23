package cdjmusiclink.fakecdj

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

class DeckTest {
    private fun deck(bpm: Double = 128.0) = Deck(bpm, trackId = 101, slot = 3, trackType = 1)

    @Test
    fun advanceCountsBeatsAt128() {
        val d = deck()
        d.play(nowMs = 0)
        d.advance(4680)
        assertEquals(10, d.beatNumber)
        assertEquals(2, d.beatWithinBar)
    }

    @Test
    fun pausedDoesNotAdvance() {
        val d = deck()
        d.play(nowMs = 0)
        d.advance(1000)
        val beat = d.beatNumber
        d.pause(nowMs = 1000)
        d.advance(10_000)
        assertEquals(beat, d.beatNumber)
        assertFalse(d.playing)
        d.play(nowMs = 20_000)
        d.advance(20_000)
        assertEquals(beat, d.beatNumber)
    }

    @Test
    fun msUntilNextBeatIsPositiveAndBelowInterval() {
        val d = deck()
        d.play(nowMs = 0)
        val interval = 469 // 468.75 rounded up
        for (t in 0L..5000L step 37) {
            d.advance(t)
            val ms = d.msUntilNextBeat(t)
            assertTrue(ms in 1..interval, "t=$t ms=$ms")
        }
    }

    @Test
    fun loadResetsBeatAndKeepsPlaying() {
        val d = deck()
        d.play(nowMs = 0)
        d.advance(3000)
        d.load(7, 2, 1)
        assertEquals(1, d.beatNumber)
        assertTrue(d.playing)
        assertEquals(7, d.trackId)
        d.unload()
        assertFalse(d.trackLoaded)
        assertFalse(d.playing)
    }
}
