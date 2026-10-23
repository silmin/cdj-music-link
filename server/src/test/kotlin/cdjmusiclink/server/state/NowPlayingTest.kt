package cdjmusiclink.server.state

import cdjmusiclink.server.model.PlayState
import cdjmusiclink.server.model.Player
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NowPlayingTest {
    private fun player(n: Int, playing: Boolean = true, onAir: Boolean? = true, master: Boolean = false) = Player(
        number = n, name = "CDJ", address = "10.0.0.$n", updatedAt = 0,
        playState = if (playing) PlayState.playing else PlayState.paused,
        isPlaying = playing, isPaused = !playing, isCued = false, isLooping = false,
        isOnAir = onAir, isSynced = false, isTempoMaster = master,
        trackBpm = null, pitch = 0.0, tempo = null, beatNumber = null, beatWithinBar = null,
        position = null, track = null,
    )

    @Test
    fun prefersOnAirMasterWhenMixerPresent() {
        val players = listOf(
            player(1, onAir = false, master = true),
            player(2, onAir = true),
            player(3, onAir = true, master = true),
            player(4, playing = false, onAir = true),
        )
        assertEquals(3, NowPlaying.pick(players, mixerPresent = true)?.number)
        assertEquals(2, NowPlaying.pick(players.filter { it.number != 3 }, mixerPresent = true)?.number)
        assertNull(NowPlaying.pick(listOf(player(1, onAir = false)), mixerPresent = true))
    }

    @Test
    fun fallsBackToMasterWithoutMixer() {
        val players = listOf(
            player(1, onAir = null),
            player(2, onAir = null, master = true),
            player(3, playing = false, onAir = null, master = true),
        )
        assertEquals(2, NowPlaying.pick(players, mixerPresent = false)?.number)
        assertEquals(1, NowPlaying.pick(players.filter { it.number != 2 }, mixerPresent = false)?.number)
        assertNull(NowPlaying.pick(listOf(player(3, playing = false, onAir = null)), mixerPresent = false))
    }
}
