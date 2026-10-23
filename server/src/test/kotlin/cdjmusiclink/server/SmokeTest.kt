package cdjmusiclink.server

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

class SmokeTest {
    @Test
    fun jvmIs25() {
        assertEquals("25", System.getProperty("java.specification.version"))
    }
}
