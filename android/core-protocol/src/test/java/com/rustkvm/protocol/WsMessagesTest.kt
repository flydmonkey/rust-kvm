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
    fun parsesSerialBusy() {
        val s = parseSerialText("""{"op":"error","message":"busy","code":"busy"}""") as SerialText.Error
        assertEquals("busy", s.code)
    }
}
