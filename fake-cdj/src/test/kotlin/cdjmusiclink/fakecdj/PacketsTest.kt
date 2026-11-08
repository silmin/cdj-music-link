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
        sourcePlayer: Int? = null,
    ) = StatusParams(
        name = "fake-cdj-1", number = 1, playing = playing, master = master, synced = false, onAir = onAir,
        bpmX100 = 12800, beatNumber = 100, beatWithinBar = 3, trackId = 42, trackLoaded = trackLoaded,
        slot = 3, trackType = 1, packetCounter = 7, syncCounter = 0,
    ).let { if (sourcePlayer != null) it.copy(sourcePlayer = sourcePlayer) else it }

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
    fun statusCarriesSourcePlayer() {
        val s = CdjStatus(datagram(Packets.status(params(sourcePlayer = 4)), 50002))
        assertEquals(4, s.trackSourcePlayer)
    }

    @Test
    fun statusSourcePlayerDefaultsToOwnNumber() {
        val s = CdjStatus(datagram(Packets.status(params()), 50002))
        assertEquals(1, s.trackSourcePlayer)
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

    /** A load-track command laid out per djl-analysis "Loading Tracks": header, then D at 0x24, Dr/Sr/Tr at 0x28.., id at 0x2c, target at 0x40. */
    private fun loadCommand(target: Int = 1, source: Int = 17, slot: Int = 4, type: Int = 1, id: Int = 0x01020304, requester: Int = 17): ByteArray {
        val b = ByteArray(0x58)
        "Qspt1WmJOL".toByteArray(Charsets.US_ASCII).copyInto(b)
        b[0x0a] = 0x19
        "rekordbox".toByteArray(Charsets.US_ASCII).copyInto(b, 0x0b)
        b[0x1f] = 0x01; b[0x21] = requester.toByte(); b[0x23] = 0x34
        b[0x24] = requester.toByte()
        b[0x28] = source.toByte(); b[0x29] = slot.toByte(); b[0x2a] = type.toByte()
        b[0x2c] = (id ushr 24).toByte(); b[0x2d] = (id ushr 16).toByte(); b[0x2e] = (id ushr 8).toByte(); b[0x2f] = id.toByte()
        b[0x40] = (target - 1).toByte()
        return b
    }

    @Test
    fun parsesLoadTrackCommand() {
        val cmd = Packets.parseLoadTrack(loadCommand(target = 2, source = 17, slot = 4, type = 1, id = 0x01020304))
        assertEquals(LoadCommand(requester = 17, targetPlayer = 2, sourcePlayer = 17, slot = 4, trackType = 1, trackId = 0x01020304), cmd)
    }

    @Test
    fun ignoresOtherPacketsAsLoadCommands() {
        // a status packet, a keep-alive, a truncated command and a wrong magic are all null
        assertEquals(null, Packets.parseLoadTrack(Packets.status(params())))
        assertEquals(null, Packets.parseLoadTrack(Packets.keepAlive("fake-cdj-1", 1, mac, ip)))
        assertEquals(null, Packets.parseLoadTrack(loadCommand(), length = 0x30))
        val bad = loadCommand().also { it[0] = 'X'.code.toByte() }
        assertEquals(null, Packets.parseLoadTrack(bad))
    }

    @Test
    fun rawPacketCarriesTypeNameAndNumber() {
        val b = Packets.raw(0x17, "fake-cdj-1", 2, byteArrayOf(0x01, 0x01, 0x00, 0x00, 0x00))
        assertEquals(0x17, Packets.typeOf(b))
        assertEquals(0x1f + 5, b.size)
        assertEquals(2, b[0x21].toInt())
        assertEquals(null, Packets.typeOf("not a dj link packet".toByteArray()))
    }

    @Test
    fun keepAliveModelByte() {
        assertEquals(0x00, Packets.keepAlive("CDJ-2000NXS2", 1, mac, ip)[0x35].toInt())
        assertEquals(0x64, Packets.keepAlive("CDJ-3000", 1, mac, ip, Packets.KA_MODEL_CDJ3000)[0x35].toInt())
        // still a valid announcement for beat-link
        val a = DeviceAnnouncement(datagram(Packets.keepAlive("CDJ-2000NXS2", 1, mac, ip), 50000))
        assertEquals("CDJ-2000NXS2", a.deviceName)
    }

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun linkHandshakePacketsMatchRealCaptures() {
        // player -> rekordbox packets captured from a CDJ-3000 talking to rekordbox 7 (Slipmat testdata/rb7)
        val hello = "5173707431576d4a4f4c1043444a2d333030300000000000000000000000000100010000"
        val query = "5173707431576d4a4f4c0543444a2d33303030000000000000000000000000010002000cc0a801660000001100000004"
        val ping = "5173707431576d4a4f4c4643444a2d333030300000000000000000000000000100020004020400c0"
        assertEquals(hex(hello).toList(), Packets.hello("CDJ-3000", 1).toList())
        val ip = byteArrayOf(192.toByte(), 168.toByte(), 1, 0x66)
        assertEquals(hex(query).toList(), Packets.mediaQuery("CDJ-3000", 2, ip, 0x11, 4).toList())
        assertEquals(hex(ping).toList(), Packets.linkPing("CDJ-3000", 2).toList())
    }

    @Test
    fun recognisesRekordboxKeepAlive() {
        // rekordbox 7 keep-alive: device 17, type byte 0x34 = 04
        val rb = "5173707431576d4a4f4c060072656b6f7264626f780000000000000000000000010300361101bcd074683ea4c0a844370401000004" + "08"
        assertEquals(0x11 to 0x04, Packets.parseKeepAlive(hex(rb)))
        assertEquals(1 to 0x01, Packets.parseKeepAlive(Packets.keepAlive("CDJ-2000NXS2", 1, mac, ip)))
        assertEquals(null, Packets.parseKeepAlive(Packets.status(params())))
    }
}
