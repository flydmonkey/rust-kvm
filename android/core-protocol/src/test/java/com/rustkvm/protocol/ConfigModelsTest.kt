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

    @Test
    fun parsesDevicesAndIgnoresUnknownFields() {
        val devices = parseDevices(
            """
            {
              "video":{"items":[{"index":"0","name":"USB Video","extra":true}],"error":null},
              "audio":{"items":[{"name":"USB Audio","sampleRate":96000,"channels":1}],"error":null},
              "serial":{"items":[{"name":"COM3","description":"USB-SERIAL CH340"}],"error":"占用中"},
              "futureField":"ignored"
            }
            """.trimIndent(),
        )

        assertEquals("USB Video", devices.video.items.single().name)
        assertEquals(96000, devices.audio.items.single().sampleRate)
        assertEquals("COM3", devices.serial.items.single().name)
        assertEquals("占用中", devices.serial.error)
    }
}
