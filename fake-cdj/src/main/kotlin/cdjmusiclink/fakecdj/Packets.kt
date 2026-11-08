/*
 * This file is distributed under the Eclipse Public License 2.0 (see THIRD_PARTY_LICENSES/EPL-2.0.txt),
 * not the MIT License that covers the rest of cdj-music-link, because the packet byte templates below are
 * reproduced from beat-link's VirtualCdj.java, copyright Deep Symmetry, LLC. See NOTICE.
 */
package cdjmusiclink.fakecdj

/**
 * Parameters of one CDJ status packet. [bpmX100] is the BPM times 100, [pitch] is the 24-bit-ish pitch value where
 * 0x100000 means 0 %. When [trackLoaded] is false, track id, slot and type are written as 0 and the BPM as 0xffff.
 */
data class StatusParams(
    val name: String,
    val number: Int,
    val playing: Boolean,
    val master: Boolean,
    val synced: Boolean,
    val onAir: Boolean,
    val bpmX100: Int,
    val pitch: Int = Packets.PITCH_NEUTRAL,
    val beatNumber: Int,
    val beatWithinBar: Int,
    val trackId: Int,
    val trackLoaded: Boolean,
    val slot: Int,
    val trackType: Int,
    val packetCounter: Int,
    val syncCounter: Int,
    /** Player the track was loaded from (status offset 0x28); defaults to this device. Written as 0 when unloaded. */
    val sourcePlayer: Int = number,
)

/** A decoded load-track command: load [trackId] from [sourcePlayer]'s [slot] on [targetPlayer]. */
data class LoadCommand(
    val requester: Int,
    val targetPlayer: Int,
    val sourcePlayer: Int,
    val slot: Int,
    val trackType: Int,
    val trackId: Int,
)

/**
 * Builders for the three UDP packet kinds a CDJ emits: keep-alive, status and beat. Pure functions, no sockets.
 *
 * The byte templates are derived from beat-link's VirtualCdj (Deep Symmetry, EPL-2.0); see NOTICE.
 * All offsets are absolute packet offsets; multi-byte integers are big-endian.
 */
object Packets {
    const val PITCH_NEUTRAL = 0x100000

    const val KEEP_ALIVE_SIZE = 54
    const val STATUS_SIZE = 284
    const val BEAT_SIZE = 96

    private const val MAGIC = "Qspt1WmJOL"
    private const val NAME_LENGTH = 20
    private const val HEADER_SIZE = 0x1f

    private const val TYPE_OFFSET = 0x0a
    private const val NAME_OFFSET = 0x0b
    private const val KEEP_ALIVE_NAME_OFFSET = 0x0c
    private const val TYPE_STATUS: Byte = 0x0a
    private const val TYPE_BEAT: Byte = 0x28
    private const val TYPE_LOAD_TRACK: Byte = 0x19

    // load-track command (sent to port 50002 by rekordbox or a virtual CDJ; offsets per djl-analysis "Loading Tracks")
    private const val LOAD_MIN_SIZE = 0x41
    private const val LOAD_REQUESTER = 0x24
    private const val LOAD_SOURCE_PLAYER = 0x28
    private const val LOAD_SLOT = 0x29
    private const val LOAD_TRACK_TYPE = 0x2a
    private const val LOAD_TRACK_ID = 0x2c
    private const val LOAD_TARGET = 0x40 // zero-based target player

    // keep-alive
    private const val KA_NUMBER = 0x24
    private const val KA_MAC = 0x26
    private const val KA_IP = 0x2c
    private const val KA_MODEL = 0x35
    const val KA_MODEL_CDJ2000 = 0x00
    const val KA_MODEL_CDJ3000 = 0x64

    // status
    private const val ST_NUMBER_A = 0x21
    private const val ST_NUMBER_B = 0x24
    private const val ST_PLAYING = 0x27
    private const val ST_SOURCE_PLAYER = 0x28
    private const val ST_SLOT = 0x29
    private const val ST_TRACK_TYPE = 0x2a
    private const val ST_TRACK_ID = 0x2c
    private const val ST_P1 = 0x7b
    private const val ST_SYNC_COUNTER = 0x84
    private const val ST_FLAGS = 0x89
    private const val ST_P2 = 0x8b
    private const val ST_PITCH_A = 0x8c
    private const val ST_BPM = 0x92
    private const val ST_PITCH_B = 0x98
    private const val ST_P3 = 0x9d
    private const val ST_MASTER = 0x9e
    private const val ST_MASTER_HANDOFF = 0x9f
    private const val ST_BEAT_NUMBER = 0xa0
    private const val ST_BEAT_IN_BAR = 0xa6
    private const val ST_PACKET_COUNTER = 0xc8

    private const val FLAGS_BASE = 0x84
    private const val FLAG_PLAYING = 0x40
    private const val FLAG_MASTER = 0x20
    private const val FLAG_SYNCED = 0x10
    private const val FLAG_ON_AIR = 0x08
    private const val NO_BPM = 0xffff
    private const val NO_MASTER_HANDOFF = 0xff

    // beat
    private const val BT_NUMBER_A = 0x21
    private const val BT_NEXT_BEAT = 0x24
    private const val BT_TWO_BEATS = 0x28
    private const val BT_NEXT_BAR = 0x2c
    private const val BT_FOUR_BEATS = 0x30
    private const val BT_SECOND_BAR = 0x34
    private const val BT_EIGHT_BEATS = 0x38
    private const val BT_PITCH = 0x54
    private const val BT_BPM = 0x5a
    private const val BT_BEAT_IN_BAR = 0x5c
    private const val BT_NUMBER_B = 0x5f

    // Templates from beat-link VirtualCdj (keepAliveBytes, STATUS_PAYLOAD, BEAT_PAYLOAD).
    private val KEEP_ALIVE_TEMPLATE = hex(
        "5173707431576d4a4f4c0600626561742d6c696e6b000000000000000000000001020036000100000000000000000000" +
            "020000000164",
    )
    private val STATUS_PAYLOAD = hex(
        "01040000f800000100000301000000000100000001000000000000a00000000000000000000000000000000000000000" +
        "000000000000000000000000000000000000000000000000000100040400000004000000040001000000000000312e34" +
        "3300000000000000000000ff0000100000800000007fffffff00100000000000000000000001ff000000000000000000" +
        "00000000000000010000000000000000000010000000100000000000000f010000123456780000000101010101000000" +
        "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
        "0000000015000007610000062f",
    )
    private val BEAT_PAYLOAD = hex(
        "01000d003c010101010202020210101010040404042020202008080808ffffffffffffffffffffffffffffffffffffff" +
            "ffffffffff00100000000000000b00000d",
    )

    /**
     * [model] is the last byte (0x35): djl-analysis shows `00` for CDJ-2000-class players and `64` for the
     * CDJ-3000 (beat-link's template, which we copied, uses `64`). Default `00` to match the CDJ-2000NXS2 name.
     */
    fun keepAlive(name: String, number: Int, mac: ByteArray, ip: ByteArray, model: Int = KA_MODEL_CDJ2000): ByteArray {
        require(number in 1..255) { "device number must be 1..255: $number" }
        require(mac.size == 6) { "mac must be 6 bytes" }
        require(ip.size == 4) { "ip must be 4 bytes" }
        require(model in 0..255) { "model byte must be 0..255: $model" }
        val p = KEEP_ALIVE_TEMPLATE.copyOf()
        putName(p, KEEP_ALIVE_NAME_OFFSET, name)
        p[KA_NUMBER] = number.toByte()
        mac.copyInto(p, KA_MAC)
        ip.copyInto(p, KA_IP)
        p[KA_MODEL] = model.toByte()
        check(p.size == KEEP_ALIVE_SIZE)
        return p
    }

    fun status(p: StatusParams): ByteArray {
        require(p.number in 1..255) { "device number must be 1..255: ${p.number}" }
        require(p.beatWithinBar in 1..4) { "beatWithinBar must be 1..4: ${p.beatWithinBar}" }
        require(p.trackLoaded || !p.playing) { "cannot be playing without a loaded track" }
        val b = packet(TYPE_STATUS, p.name, STATUS_PAYLOAD)
        val number = p.number.toByte()
        b[ST_NUMBER_A] = number
        b[ST_NUMBER_B] = number
        b[ST_PLAYING] = flag(p.playing)
        val loaded = p.trackLoaded
        b[ST_SOURCE_PLAYER] = if (loaded) p.sourcePlayer.toByte() else 0
        b[ST_SLOT] = if (loaded) p.slot.toByte() else 0
        b[ST_TRACK_TYPE] = if (loaded) p.trackType.toByte() else 0
        putInt(b, ST_TRACK_ID, if (loaded) p.trackId else 0)
        putShort(b, ST_BPM, if (loaded) p.bpmX100 else NO_BPM)
        b[ST_P1] = (if (p.playing) 3 else 5).toByte()
        putInt(b, ST_SYNC_COUNTER, p.syncCounter)
        b[ST_FLAGS] = (
            FLAGS_BASE +
                (if (p.playing) FLAG_PLAYING else 0) +
                (if (p.master) FLAG_MASTER else 0) +
                (if (p.synced) FLAG_SYNCED else 0) +
                (if (p.onAir) FLAG_ON_AIR else 0)
            ).toByte()
        b[ST_P2] = (if (p.playing) 0x7a else 0x7e).toByte()
        putInt(b, ST_PITCH_A, p.pitch)
        putInt(b, ST_PITCH_B, p.pitch)
        b[ST_P3] = (if (p.playing) 9 else 1).toByte()
        b[ST_MASTER] = flag(p.master)
        b[ST_MASTER_HANDOFF] = NO_MASTER_HANDOFF.toByte()
        putInt(b, ST_BEAT_NUMBER, p.beatNumber)
        b[ST_BEAT_IN_BAR] = p.beatWithinBar.toByte()
        putInt(b, ST_PACKET_COUNTER, p.packetCounter)
        check(b.size == STATUS_SIZE)
        return b
    }

    fun beat(
        name: String,
        number: Int,
        bpmX100: Int,
        pitch: Int,
        beatWithinBar: Int,
        beatIntervalMs: Int,
        nextBarInMs: Int,
    ): ByteArray {
        require(number in 1..255) { "device number must be 1..255: $number" }
        require(beatWithinBar in 1..4) { "beatWithinBar must be 1..4: $beatWithinBar" }
        val b = packet(TYPE_BEAT, name, BEAT_PAYLOAD)
        b[BT_NUMBER_A] = number.toByte()
        b[BT_NUMBER_B] = number.toByte()
        putInt(b, BT_NEXT_BEAT, beatIntervalMs)
        putInt(b, BT_TWO_BEATS, beatIntervalMs * 2)
        putInt(b, BT_NEXT_BAR, nextBarInMs)
        putInt(b, BT_FOUR_BEATS, beatIntervalMs * 4)
        putInt(b, BT_SECOND_BAR, nextBarInMs + beatIntervalMs * 4)
        putInt(b, BT_EIGHT_BEATS, beatIntervalMs * 8)
        putInt(b, BT_PITCH, pitch)
        putShort(b, BT_BPM, bpmX100)
        b[BT_BEAT_IN_BAR] = beatWithinBar.toByte()
        check(b.size == BEAT_SIZE)
        return b
    }

    // rekordbox LINK handshake, player side (layouts from real rekordbox 7 + CDJ captures published by Slipmat)
    private const val TYPE_HELLO: Byte = 0x10 // player -> rekordbox, 36 bytes
    private const val TYPE_MEDIA_QUERY: Byte = 0x05 // player -> rekordbox, 48 bytes
    private const val TYPE_LINK_PING: Byte = 0x46 // player -> rekordbox, 40 bytes
    const val SLOT_COLLECTION = 4
    const val KEEP_ALIVE_TYPE_OFFSET = 0x34 // 01 CDJ, 02 mixer, 04 rekordbox
    const val DEVICE_TYPE_REKORDBOX = 0x04

    /** Type 0x10: a player greets a rekordbox it has just seen. Payload `01 00 D 00 00`. */
    fun hello(name: String, number: Int): ByteArray =
        packet(TYPE_HELLO, name, byteArrayOf(0x01, 0x00, number.toByte(), 0x00, 0x00))

    /** Type 0x05: ask [target] about the media in [slot] (4 = rekordbox collection); carries our own IPv4. */
    fun mediaQuery(name: String, number: Int, ownIp: ByteArray, target: Int, slot: Int): ByteArray {
        require(ownIp.size == 4) { "ip must be 4 bytes" }
        val payload = ByteArray(5 + 12)
        payload[0] = 0x01; payload[2] = number.toByte(); payload[4] = 0x0c
        ownIp.copyInto(payload, 5)
        payload[12] = target.toByte()
        payload[16] = slot.toByte()
        return packet(TYPE_MEDIA_QUERY, name, payload)
    }

    /** Type 0x46: the single "link ping" a player sends before browsing a rekordbox collection. */
    fun linkPing(name: String, number: Int, slot: Int = SLOT_COLLECTION): ByteArray =
        packet(TYPE_LINK_PING, name, byteArrayOf(0x01, 0x00, number.toByte(), 0x00, 0x04, number.toByte(), slot.toByte(), 0x00, 0xc0.toByte()))

    /** Device number and device-type byte of a keep-alive (type 0x06), or null. */
    fun parseKeepAlive(bytes: ByteArray, length: Int = bytes.size): Pair<Int, Int>? {
        if (typeOf(bytes, length) != 0x06 || length < KEEP_ALIVE_SIZE) return null
        return (bytes[KA_NUMBER].toInt() and 0xff) to (bytes[KEEP_ALIVE_TYPE_OFFSET].toInt() and 0xff)
    }

    /** The packet type byte (offset 0x0a) when [bytes] carries the Pro DJ Link magic, else null. */
    fun typeOf(bytes: ByteArray, length: Int = bytes.size): Int? {
        if (length <= TYPE_OFFSET) return null
        val magic = MAGIC.toByteArray(Charsets.US_ASCII)
        for (i in magic.indices) if (bytes[i] != magic[i]) return null
        return bytes[TYPE_OFFSET].toInt() and 0xff
    }

    /** Header + [payload] for an arbitrary [type]; [number] is written at payload offset 2 when the payload has one. */
    fun raw(type: Int, name: String, number: Int, payload: ByteArray): ByteArray {
        val p = payload.copyOf()
        if (p.size > 2) p[2] = number.toByte()
        return packet(type.toByte(), name, p)
    }

    fun hex(bytes: ByteArray, length: Int = bytes.size): String =
        bytes.take(length).joinToString("") { "%02x".format(it) }

    /**
     * Parses a load-track command (type 0x19) addressed to a player, or returns null for anything else.
     * Only [length] bytes of [bytes] are valid (the datagram length).
     */
    fun parseLoadTrack(bytes: ByteArray, length: Int = bytes.size): LoadCommand? {
        if (length < LOAD_MIN_SIZE) return null
        val magic = MAGIC.toByteArray(Charsets.US_ASCII)
        for (i in magic.indices) if (bytes[i] != magic[i]) return null
        if (bytes[TYPE_OFFSET] != TYPE_LOAD_TRACK) return null
        fun u8(i: Int) = bytes[i].toInt() and 0xff
        val id = (u8(LOAD_TRACK_ID) shl 24) or (u8(LOAD_TRACK_ID + 1) shl 16) or
            (u8(LOAD_TRACK_ID + 2) shl 8) or u8(LOAD_TRACK_ID + 3)
        return LoadCommand(
            requester = u8(LOAD_REQUESTER),
            targetPlayer = u8(LOAD_TARGET) + 1,
            sourcePlayer = u8(LOAD_SOURCE_PLAYER),
            slot = u8(LOAD_SLOT),
            trackType = u8(LOAD_TRACK_TYPE),
            trackId = id,
        )
    }

    private fun packet(type: Byte, name: String, payload: ByteArray): ByteArray {
        val b = ByteArray(HEADER_SIZE + payload.size)
        MAGIC.toByteArray(Charsets.US_ASCII).copyInto(b)
        b[TYPE_OFFSET] = type
        putName(b, NAME_OFFSET, name)
        payload.copyInto(b, HEADER_SIZE)
        return b
    }

    private fun putName(dst: ByteArray, offset: Int, name: String) {
        val bytes = name.toByteArray(Charsets.US_ASCII)
        bytes.copyInto(dst, offset, 0, minOf(bytes.size, NAME_LENGTH))
        // clear any template remainder after the name
        for (i in bytes.size.coerceAtMost(NAME_LENGTH) until NAME_LENGTH) dst[offset + i] = 0
    }

    private fun flag(v: Boolean): Byte = if (v) 1 else 0

    private fun putInt(dst: ByteArray, offset: Int, v: Int) {
        for (i in 0 until 4) dst[offset + i] = (v ushr (24 - 8 * i)).toByte()
    }

    private fun putShort(dst: ByteArray, offset: Int, v: Int) {
        dst[offset] = (v ushr 8).toByte()
        dst[offset + 1] = v.toByte()
    }

    private fun hex(s: String): ByteArray {
        check(s.length % 2 == 0) { "odd number of hex digits" }
        return decodeHex(s)
    }

    private fun decodeHex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}
