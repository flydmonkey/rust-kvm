package com.rustkvm.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class WsMessagesTest {
    @Test
    fun parsesH264CodecHeader() {
        val v = parseVideoText("""{"op":"codec","codec":"h264","avcCodec":"avc1.4D0428"}""")
        val c = v as VideoText.Codec
        assertEquals("h264", c.codec)
        assertEquals("avc1.4D0428", c.avcCodec)
        assertEquals(null, c.fallbackReason)
    }

    @Test
    fun parsesMjpegFallbackReason() {
        val v = parseVideoText("""{"op":"codec","codec":"mjpeg","fallbackReason":"编码器没能在 5 秒内就绪"}""")
        val c = v as VideoText.Codec
        assertEquals("mjpeg", c.codec)
        assertEquals("编码器没能在 5 秒内就绪", c.fallbackReason)
    }

    @Test
    fun parsesIdle() {
        val v = parseVideoText("""{"op":"idle","skipped":12}""") as VideoText.Idle
        assertEquals(12L, v.skipped)
    }

    @Test
    fun parsesAudioFormat() {
        val a = parseAudioText("""{"op":"format","sampleRate":48000,"channels":2,"format":"s16le"}""")
        assertEquals(48000, a!!.sampleRate)
        assertEquals(2, a.channels)
    }

    @Test
    fun parsesAudioError() {
        val message = parseAudioError("""{"op":"error","message":"音频已禁用"}""")

        assertEquals("音频已禁用", message)
    }

    @Test
    fun parsesSerialBusy() {
        val s = parseSerialText("""{"op":"error","message":"busy","code":"busy"}""") as SerialText.Error
        assertEquals("busy", s.code)
    }
}
