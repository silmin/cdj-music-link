package cdjmusiclink.server.link

import cdjmusiclink.server.Config
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ConfigTest {
    @Test
    fun fromEnvDefaults() {
        val c = Config.fromEnv(emptyMap())
        assertEquals(17080, c.port)
        assertEquals("cdj-music-link", c.deviceName)
        assertNull(c.deviceNumber)
        assertEquals("info", c.logLevel)
    }

    @Test
    fun fromEnvParsesNumber() {
        val c = Config.fromEnv(mapOf("DEVICE_NUMBER" to "4", "PORT" to "9000", "DEVICE_NAME" to "x", "LOG_LEVEL" to "debug"))
        assertEquals(4, c.deviceNumber)
        assertEquals(9000, c.port)
        assertEquals("x", c.deviceName)
        assertEquals("debug", c.logLevel)
    }

    @Test
    fun fromEnvRejectsInvalidNumber() {
        for (v in listOf("0", "16", "abc", "")) {
            assertThrows<IllegalArgumentException> { Config.fromEnv(mapOf("DEVICE_NUMBER" to v)) }
        }
    }
}
