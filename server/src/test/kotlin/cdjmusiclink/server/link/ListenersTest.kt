package cdjmusiclink.server.link

import cdjmusiclink.fakecdj.Packets
import org.deepsymmetry.beatlink.Beat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.net.DatagramPacket
import java.net.InetAddress

class ListenersTest {
    private fun beat(beatWithinBar: Int = 1): Beat {
        val bytes = Packets.beat("fake-cdj-1", 1, 12800, 0x100000, beatWithinBar, 468, 1404)
        return Beat(DatagramPacket(bytes, bytes.size, InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 10)), 50001))
    }

    @Test
    fun beatToEvent() {
        val e = Listeners.beatEventOf(beat(), 1234L, 5 to "#00ff00", 999L, true, 42)
        assertEquals(1, e.player)
        assertEquals(128.0, e.tempo!!, 0.001)
        assertEquals(1, e.beatWithinBar)
        assertEquals(468, e.nextBeatInMs)
        assertEquals(1404, e.nextBarInMs)
        assertEquals(42, e.beatNumber)
        assertEquals(true, e.isTempoMaster)
        assertEquals(1234L, e.position!!.ms)
        assertEquals(5, e.waveform?.height)
        assertEquals("#00ff00", e.waveform?.color)
        assertEquals(999L, e.receivedAt)
    }

    @Test
    fun beatToEventWithoutOptionals() {
        val e = Listeners.beatEventOf(beat(), null, null, 1L, false)
        assertNull(e.position)
        assertNull(e.waveform)
        assertNull(e.beatNumber)
    }
}
