package cdjmusiclink.server

data class Config(
    val port: Int = 17080,
    val deviceName: String = "cdj-music-link",
    val deviceNumber: Int? = null,
    val logLevel: String = "info",
) {
    companion object {
        fun fromEnv(env: Map<String, String>): Config {
            // 空文字や空白だけの値は未設定として扱う(compose の `KEY=` を既定値にするため)。
            fun value(key: String): String? = env[key]?.trim()?.takeIf { it.isNotEmpty() }
            val port = value("PORT")?.let {
                it.toIntOrNull()?.takeIf { p -> p in 1..65535 } ?: throw IllegalArgumentException("PORT must be 1..65535: '$it'")
            } ?: 17080
            val number = value("DEVICE_NUMBER")?.let {
                it.toIntOrNull()?.takeIf { n -> n in 1..15 }
                    ?: throw IllegalArgumentException("DEVICE_NUMBER must be 1..15: '$it'")
            }
            return Config(
                port = port,
                deviceName = value("DEVICE_NAME") ?: "cdj-music-link",
                deviceNumber = number,
                logLevel = value("LOG_LEVEL") ?: "info",
            )
        }

        fun fromEnv(): Config = fromEnv(System.getenv())
    }
}
