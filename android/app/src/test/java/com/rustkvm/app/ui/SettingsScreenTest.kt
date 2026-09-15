package com.rustkvm.app.ui

import com.rustkvm.protocol.KvmConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsScreenTest {
    @Test
    fun formKeepsEveryConfigFieldWhenSaving() {
        val form = SettingsForm.from(KvmConfig.DEFAULT).copy(
            videoDevice = "Capture Card",
            width = "1280",
            height = "720",
            fps = "25",
            codec = "h264",
            bitrateKbps = "2000",
            audioDevice = "USB Audio",
            audioEnabled = false,
            audioNarrowband = true,
            serialPort = "COM7",
            baudRate = "9600",
            mouseMode = "relative",
        )

        val config = form.toConfig()

        assertEquals("Capture Card", config.video.device)
        assertEquals(1280, config.video.width)
        assertEquals(720, config.video.height)
        assertEquals(25, config.video.fps)
        assertEquals("h264", config.video.codec)
        assertEquals(2000, config.video.bitrateKbps)
        assertEquals("USB Audio", config.audio.device)
        assertEquals(false, config.audio.enabled)
        assertEquals(true, config.audio.narrowband)
        assertEquals("COM7", config.serial.port)
        assertEquals(9600, config.serial.baudRate)
        assertEquals("relative", config.serial.mouseMode)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonNumericDimensions() {
        SettingsForm.from(KvmConfig.DEFAULT).copy(width = "wide").toConfig()
    }
}
