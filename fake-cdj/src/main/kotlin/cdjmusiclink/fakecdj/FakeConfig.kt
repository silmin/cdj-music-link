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
            )
        }
    }
}
