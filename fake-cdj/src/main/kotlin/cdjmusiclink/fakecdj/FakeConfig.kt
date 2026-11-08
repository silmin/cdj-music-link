package cdjmusiclink.fakecdj

data class FakeConfig(
    val number: Int,
    val name: String,
    val bpm: Double,
    val trackId: Int,
    val slot: Int,
    val trackType: Int,
    val autoplay: Boolean,
    val iface: String?,
    val controlPort: Int = 17090,
    /** Player the loaded track comes from; null means this device's own number. */
    val sourcePlayer: Int? = null,
    /**
     * Experimental: when a packet of type [linkRequestType] arrives on 50002, reply to the sender with a packet of
     * type [linkReplyType] whose payload (after the 0x1f header) is [linkReplyPayload] with our device number
     * written at payload offset 2. Used to probe rekordbox 7's undocumented LINK EXPORT handshake (type 0x16).
     */
    val linkRequestType: Int? = null,
    val linkReplyType: Int = 0x17,
    val linkReplyPayload: ByteArray = byteArrayOf(),
) {
    companion object {
        fun fromEnv(env: Map<String, String>): FakeConfig {
            val number = env["FAKE_NUMBER"]?.toInt() ?: 1
            return FakeConfig(
                number = number,
                name = env["FAKE_NAME"] ?: "fake-cdj-$number",
                bpm = env["FAKE_BPM"]?.toDouble() ?: 128.0,
                trackId = env["FAKE_TRACK_ID"]?.toInt() ?: 101,
                slot = env["FAKE_SLOT"]?.toInt() ?: 3,
                trackType = env["FAKE_TRACK_TYPE"]?.toInt() ?: 1,
                autoplay = env["FAKE_AUTOPLAY"]?.lowercase() in setOf("true", "1", "yes"),
                iface = env["FAKE_IFACE"]?.takeIf { it.isNotBlank() },
                controlPort = env["FAKE_CONTROL_PORT"]?.toInt() ?: 17090,
                sourcePlayer = env["FAKE_SOURCE_PLAYER"]?.takeIf { it.isNotBlank() }?.toInt(),
                linkRequestType = env["FAKE_LINK_REQUEST_TYPE"]?.takeIf { it.isNotBlank() }?.removePrefix("0x")?.toInt(16),
                linkReplyType = env["FAKE_LINK_REPLY_TYPE"]?.takeIf { it.isNotBlank() }?.removePrefix("0x")?.toInt(16) ?: 0x17,
                linkReplyPayload = env["FAKE_LINK_REPLY_PAYLOAD"]?.filter { !it.isWhitespace() }?.takeIf { it.isNotEmpty() }
                    ?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray() ?: byteArrayOf(),
            )
        }
    }
}
