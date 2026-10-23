package cdjmusiclink.fakecdj

import org.deepsymmetry.beatlink.Beat
import org.deepsymmetry.beatlink.CdjStatus
import org.deepsymmetry.beatlink.DeviceAnnouncement
import java.net.DatagramPacket
import java.net.InetAddress
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

class PacketsTest {
    private val ip = byteArrayOf(192.toByte(), 168.toByte(), 1, 10)
    private val mac = byteArrayOf(0x02, 0x11, 0x22, 0x33, 0x44, 0x55)

    private fun datagram(bytes: ByteArray, port: Int) =
        DatagramPacket(bytes, bytes.size, InetAddress.getByAddress(ip), port)

    private fun params(
        playing: Boolean = true,
        master: Boolean = true,
        onAir: Boolean = true,
        trackLoaded: Boolean = true,
    ) = StatusParams(
        name = "fake-cdj-1", number = 1, playing = playing, master = master, synced = false, onAir = onAir,
        bpmX100 = 12800, beatNumber = 100, beatWithinBar = 3, trackId = 42, trackLoaded = trackLoaded,
        slot = 3, trackType = 1, packetCounter = 7, syncCounter = 0,
    )

    @Test
    fun keepAliveParsesNameAndNumber() {
        val bytes = Packets.keepAlive("fake-cdj-1", 1, mac, ip)
        val a = DeviceAnnouncement(datagram(bytes, 50000))
        assertEquals("fake-cdj-1", a.deviceName)
        assertEquals(1, a.deviceNumber)
        assertEquals(InetAddress.getByAddress(ip), a.address)
    }

    @Test
    fun statusParsesPlayingFlagsAndTrack() {
        val s = CdjStatus(datagram(Packets.status(params()), 50002))
        assertTrue(s.isPlaying)
        assertTrue(s.isTempoMaster)
        assertTrue(s.isOnAir)
        assertEquals(12800, s.bpm)
        assertEquals(100, s.beatNumber)
        assertEquals(3, s.beatWithinBar)
        assertEquals(42, s.rekordboxId)
        assertEquals(CdjStatus.TrackSourceSlot.USB_SLOT, s.trackSourceSlot)
        assertEquals(CdjStatus.TrackType.REKORDBOX, s.trackType)
        assertEquals(128.0, s.effectiveTempo, 0.001)
    }

    @Test
    fun statusPausedHasNoPlayingFlag() {
        val s = CdjStatus(datagram(Packets.status(params(playing = false, master = false, onAir = false)), 50002))
        assertFalse(s.isPlaying)
        assertTrue(s.isPaused)
        assertFalse(s.isTempoMaster)
        assertFalse(s.isOnAir)
    }

    @Test
    fun beatParses() {
        val bytes = Packets.beat("fake-cdj-1", 1, 12800, 0x100000, 1, 468, 1404)
        val b = Beat(datagram(bytes, 50001))
        assertEquals(12800, b.bpm)
        assertEquals(468L, b.nextBeat)
        assertEquals(1404L, b.nextBar)
        assertEquals(1, b.beatWithinBar)
        assertEquals(128.0, b.effectiveTempo, 0.001)
    }

    @Test
    fun packetLengths() {
        assertEquals(54, Packets.keepAlive("x", 1, mac, ip).size)
        assertEquals(284, Packets.status(params()).size)
        assertEquals(96, Packets.beat("x", 1, 12800, 0x100000, 1, 468, 1404).size)
    }

    @Test
    fun unloadedStatusClearsTrackFields() {
        val unloaded = params(playing = false, master = false, onAir = false, trackLoaded = false)
        val s = CdjStatus(datagram(Packets.status(unloaded), 50002))
        assertEquals(0, s.rekordboxId)
        assertEquals(CdjStatus.TrackSourceSlot.NO_TRACK, s.trackSourceSlot)
        assertEquals(CdjStatus.TrackType.NO_TRACK, s.trackType)
        assertEquals(0xffff, s.bpm)
    }

    @Test
    fun statusTemplateMatchesBeatLink() {
        val b = Packets.status(params())
        fun at(from: Int, to: Int) = b.copyOfRange(from, to).map { it.toInt() and 0xff }
        // beat-link STATUS_PAYLOAD rows 0x0d0 and 0x110 (absolute offsets)
        assertEquals(listOf(0x01, 0x01, 0x01, 0x01, 0x00), at(0xd8, 0xdd))
        assertEquals(listOf(0x00, 0x15, 0x00, 0x00, 0x07, 0x61, 0x00, 0x00, 0x06, 0x2f), at(0x112, 0x11c))
    }
}
