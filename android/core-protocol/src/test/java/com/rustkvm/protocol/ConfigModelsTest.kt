package com.rustkvm.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class ConfigModelsTest {
    @Test
    fun parsesCamelCaseConfig() {
        val json = """
        {
          "video":{"device":"USB Video","width":1920,"height":1080,"fps":30,"codec":"h264","bitrateKbps":4000},
          "audio":{"device":"Digital Audio Interface","enabled":true,"narrowband":false},
          "serial":{"port":"COM3","baudRate":115200,"mouseMode":"absolute"}
        }
        """.trimIndent()
        val c = parseConfig(json)
        assertEquals("h264", c.video.codec)
        assertEquals(4000, c.video.bitrateKbps)
        assertEquals(115200, c.serial.baudRate)
        assertEquals("absolute", c.serial.mouseMode)
    }

    @Test
    fun roundTrips() {
        val original = KvmConfig.DEFAULT
        val again = parseConfig(encodeConfig(original))
        assertEquals(original, again)
    }
}
