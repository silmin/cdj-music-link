package cdjmusiclink.server.state

import cdjmusiclink.server.model.PlayState
import cdjmusiclink.server.model.Slot
import cdjmusiclink.server.model.TrackType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

fun snap(
    playing: Boolean = true,
    trackLoaded: Boolean = true,
    onAir: Boolean = true,
    master: Boolean = false,
    bpmX100: Int = 12800,
    id: Int = 5,
    source: Int = 2,
) = StatusSnapshot(
    playing = playing, paused = false, cued = false, looping = false, searching = false, atEnd = false,
    busy = false, trackLoaded = trackLoaded, onAir = onAir, synced = false, master = master,
    bpmX100 = bpmX100, pitchPct = 0.0, effectiveTempo = if (trackLoaded) 128.0 else null,
    beatNumber = 3, beatWithinBar = 3, rekordboxId = id, sourcePlayer = source,
    slot = Slot.usb, type = TrackType.rekordbox,
)

class PlayerStateTest {
    private fun state() = PlayerState(1, "CDJ-3000", "10.0.0.1")

    @Test
    fun positionIsNullBeforeTimeFinderKnows() {
        val s = state()
        s.applyStatus(snap(), mixerPresent = true, now = 10)
        assertNull(s.toDto().position)
    }

    @Test
    fun trackBecomesNullOnUnload() {
        val s = state()
        val first = s.applyStatus(snap(id = 5), true, 10)
        assertTrue(Change.TRACK in first)
        assertEquals(5, s.toDto().track?.id)
        val second = s.applyStatus(snap(trackLoaded = false, playing = false), true, 20)
        assertTrue(Change.TRACK in second)
        assertNull(s.toDto().track)
        assertEquals(PlayState.empty, s.toDto().playState)
    }

    @Test
    fun onAirNullWithoutMixer() {
        val s = state()
        s.applyStatus(snap(onAir = true), mixerPresent = false, now = 10)
        assertNull(s.toDto().isOnAir)
        s.applyStatus(snap(onAir = true), mixerPresent = true, now = 11)
        assertEquals(true, s.toDto().isOnAir)
    }

    @Test
    fun bpmAndTempoDerivation() {
        val s = state()
        s.applyStatus(snap(bpmX100 = 0xffff), true, 10)
        assertNull(s.toDto().trackBpm)
        s.applyStatus(snap(bpmX100 = 12850), true, 11)
        assertEquals(128.5, s.toDto().trackBpm)
        assertEquals(128.0, s.toDto().tempo)
    }

    @Test
    fun identicalSnapshotsProduceNoChange() {
        val s = state()
        s.applyStatus(snap(), true, 10)
        assertEquals(emptySet<Change>(), s.applyStatus(snap(), true, 11))
    }

    @Test
    fun changeKinds() {
        val s = state()
        s.applyStatus(snap(), true, 10)
        assertEquals(setOf(Change.PLAY_STATE), s.applyStatus(snap(playing = false), true, 11))
        assertEquals(setOf(Change.FLAGS), s.applyStatus(snap(playing = false, onAir = false), true, 12))
        assertEquals(setOf(Change.FLAGS, Change.MASTER), s.applyStatus(snap(playing = false, onAir = false, master = true), true, 13))
    }

    @Test
    fun positionComputesRemaining() {
        val s = state()
        s.applyStatus(snap(), true, 10)
        s.applyPosition(1000, true, 5000, 42)
        val p = s.toDto().position!!
        assertEquals(1000, p.ms); assertEquals(4000, p.remainingMs); assertEquals(42, p.updatedAt); assertTrue(p.definitive)
        s.applyPosition(1000, false, null, 43)
        assertNull(s.toDto().position!!.remainingMs)
    }

    @Test
    fun metadataReplacesStubOnlyWhenMatching() {
        val s = state()
        s.applyStatus(snap(id = 5, source = 2), true, 10)
        val stub = s.toDto().track!!
        assertNull(stub.title)
        s.applyMetadata(stub.copy(id = 9, title = "stale"))
        assertNull(s.toDto().track!!.title)
        s.applyMetadata(stub.copy(title = "Full"))
        assertEquals("Full", s.toDto().track!!.title)
        s.applyAvailability(true, null, false)
        assertTrue(s.toDto().track!!.artworkAvailable)
        s.applyMetadata(null)
        assertNull(s.toDto().track)
    }
}
