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
        for (v in listOf("0", "16", "abc", " x ")) {
            assertThrows<IllegalArgumentException> { Config.fromEnv(mapOf("DEVICE_NUMBER" to v)) }
        }
        for (v in listOf("0", "65536", "abc")) {
            assertThrows<IllegalArgumentException> { Config.fromEnv(mapOf("PORT" to v)) }
        }
    }

    @Test
    fun blankValuesMeanUnset() {
        for (v in listOf("", " ", "\t")) {
            val c = Config.fromEnv(mapOf("DEVICE_NUMBER" to v, "PORT" to v, "DEVICE_NAME" to v, "LOG_LEVEL" to v))
            assertNull(c.deviceNumber)
            assertEquals(17080, c.port)
            assertEquals("cdj-music-link", c.deviceName)
            assertEquals("info", c.logLevel)
        }
    }
}
