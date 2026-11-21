package cdjmusiclink.server.model

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

class EnumsTest {
    @Test
    fun deviceKindBoundaries() {
        assertEquals(DeviceKind.player, deviceKindOf(1))
        assertEquals(DeviceKind.mixer, deviceKindOf(0x21))
        assertEquals(DeviceKind.mixer, deviceKindOf(0x28))
        assertEquals(DeviceKind.collection, deviceKindOf(0x11))
        assertEquals(DeviceKind.collection, deviceKindOf(0x29))
    }

    @Test
    fun playingFromStateBytesWhenFlagIsOff() {
        val p1 = org.deepsymmetry.beatlink.CdjStatus.PlayState1.PLAYING
        val moving = org.deepsymmetry.beatlink.CdjStatus.PlayState2.MOVING
        val stopped = org.deepsymmetry.beatlink.CdjStatus.PlayState2.STOPPED
        assertEquals(true, isPlayingOf(true, org.deepsymmetry.beatlink.CdjStatus.PlayState1.PAUSED, stopped))
        assertEquals(true, isPlayingOf(false, p1, moving))
        assertEquals(true, isPlayingOf(false, org.deepsymmetry.beatlink.CdjStatus.PlayState1.LOOPING, moving))
        assertEquals(false, isPlayingOf(false, p1, stopped))
        assertEquals(false, isPlayingOf(false, org.deepsymmetry.beatlink.CdjStatus.PlayState1.PAUSED, moving))
    }

    private fun state(
        playing: Boolean = false, paused: Boolean = false, cued: Boolean = false,
        searching: Boolean = false, atEnd: Boolean = false, busy: Boolean = false,
        loaded: Boolean = true,
    ) = playStateOf(playing, paused, cued, searching, atEnd, busy, loaded)

    @Test
    fun playStateFromFlags() {
        assertEquals(PlayState.playing, state(playing = true))
        assertEquals(PlayState.paused, state(paused = true))
        assertEquals(PlayState.cued, state(cued = true))
        assertEquals(PlayState.searching, state(searching = true))
        assertEquals(PlayState.empty, state(playing = true, loaded = false))
        assertEquals(PlayState.ended, state(atEnd = true, playing = true))
        assertEquals(PlayState.loading, state(busy = true))
        assertEquals(PlayState.paused, state())
    }

    @Test
    fun playStatePrecedence() {
        assertEquals(PlayState.playing, state(playing = true, searching = true, cued = true, paused = true, busy = true))
        assertEquals(PlayState.searching, state(searching = true, cued = true, paused = true, busy = true))
        assertEquals(PlayState.cued, state(cued = true, paused = true, busy = true))
        assertEquals(PlayState.paused, state(paused = true, busy = true))
    }

    @Test
    fun slotAndTrackTypeMapping() {
        assertEquals(Slot.usb, slotOf(org.deepsymmetry.beatlink.CdjStatus.TrackSourceSlot.USB_SLOT))
        assertEquals(Slot.none, slotOf(org.deepsymmetry.beatlink.CdjStatus.TrackSourceSlot.UNKNOWN))
        assertEquals(TrackType.rekordbox, trackTypeOf(org.deepsymmetry.beatlink.CdjStatus.TrackType.REKORDBOX))
        assertEquals(TrackType.none, trackTypeOf(org.deepsymmetry.beatlink.CdjStatus.TrackType.UNKNOWN))
    }
}
