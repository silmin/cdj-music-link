package cdjmusiclink.server

data class Config(
    val port: Int = 17080,
    val deviceName: String = "cdj-music-link",
    val deviceNumber: Int? = null,
    val logLevel: String = "info",
) {
    companion object {
        fun fromEnv(env: Map<String, String>): Config {
            val port = env["PORT"]?.let {
                it.toIntOrNull()?.takeIf { p -> p in 1..65535 } ?: throw IllegalArgumentException("PORT must be 1..65535: '$it'")
            } ?: 17080
            val number = env["DEVICE_NUMBER"]?.let {
                it.toIntOrNull()?.takeIf { n -> n in 1..15 }
                    ?: throw IllegalArgumentException("DEVICE_NUMBER must be 1..15: '$it'")
            }
            return Config(
                port = port,
                deviceName = env["DEVICE_NAME"] ?: "cdj-music-link",
                deviceNumber = number,
                logLevel = env["LOG_LEVEL"] ?: "info",
            )
        }

        fun fromEnv(): Config = fromEnv(System.getenv())
    }
}
