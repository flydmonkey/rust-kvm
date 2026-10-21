package com.rustkvm.host.uvc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class UvcFrameAssemblerTest {
    private val frames = mutableListOf<ByteArray>()
    private val assembler = UvcFrameAssembler(maxFrameBytes = 64) { frames += it }

    @Test
    fun endOfFrameBitCompletesAFrame() {
        feed(payload(fid = 0, 0xFF, 0xD8, 0x11))
        feed(payload(fid = 0, 0x22, 0xFF, 0xD9, eof = true))
        assertArrayEquals(bytes(0xFF, 0xD8, 0x11, 0x22, 0xFF, 0xD9), frames.single())
    }

    @Test
    fun frameIdToggleCompletesAFrameWhenEofIsNeverSet() {
        feed(payload(fid = 0, 0xFF, 0xD8, 0x01))
        feed(payload(fid = 0, 0x02))
        assertTrue(frames.isEmpty())
        feed(payload(fid = 1, 0xFF, 0xD8, 0x03, 0xFF, 0xD9))
        assertArrayEquals(bytes(0xFF, 0xD8, 0x01, 0x02), frames.single())
        feed(payload(fid = 0, 0xFF, 0xD8, 0x04))
        assertArrayEquals(bytes(0xFF, 0xD8, 0x03, 0xFF, 0xD9), frames[1])
    }

    @Test
    fun framesWithAnErrorPayloadAreDroppedWhole() {
        feed(payload(fid = 0, 0xFF, 0xD8, 0x01))
        feed(payload(fid = 0, 0x02, error = true))
        feed(payload(fid = 0, 0x03, eof = true))
        feed(payload(fid = 1, 0xFF, 0xD8, 0x09, 0xFF, 0xD9, eof = true))
        assertArrayEquals(bytes(0xFF, 0xD8, 0x09, 0xFF, 0xD9), frames.single())
        assertEquals(1, assembler.droppedFrames)
    }

    @Test
    fun nonJpegAndOversizeFramesAreDropped() {
        feed(payload(fid = 0, 0x00, 0x01, 0x02, 0x03, eof = true))
        feed(payload(fid = 1, 0xFF, 0xD8, *IntArray(70), eof = true))
        assertTrue(frames.isEmpty())
        assertEquals(2, assembler.droppedFrames)
    }

    @Test
    fun headerOnlyAndMalformedPayloadsAreHarmless() {
        feed(bytes(0x02, 0x00))
        feed(bytes(0x0C, 0x00, 0xFF))
        feed(bytes(0x01))
        feed(payload(fid = 0, 0xFF, 0xD8, 0xFF, 0xD9))
        feed(bytes(0x02, 0x02))
        assertArrayEquals(bytes(0xFF, 0xD8, 0xFF, 0xD9), frames.single())
    }

    @Test
    fun captureStreamPullsPayloadsUntilAFrameIsReady() {
        val reader = ScriptedReader(
            listOf(payload(fid = 0, 0xFF, 0xD8, 0x01)),
            emptyList(),
            listOf(payload(fid = 0, 0x02, eof = true), payload(fid = 1, 0xFF, 0xD8, 0x03, 0xFF, 0xD9, eof = true)),
        )
        val stream = UvcCaptureStream(reader, 1920, 1080, maxFrameBytes = 64, clock = reader::now)
        assertArrayEquals(bytes(0xFF, 0xD8, 0x01, 0x02), stream.readFrame())
        assertArrayEquals(bytes(0xFF, 0xD8, 0x03, 0xFF, 0xD9), stream.readFrame())
    }

    @Test
    fun captureStreamGivesUpWhenTheCardGoesSilent() {
        val reader = ScriptedReader()
        val stream = UvcCaptureStream(reader, 1920, 1080, maxFrameBytes = 64, frameTimeoutMs = 3_000, clock = reader::now)
        val error = assertThrows(IOException::class.java) { stream.readFrame() }
        assertTrue(error.message!!.contains("3 秒"))
        stream.close()
        assertTrue(reader.closed)
        assertThrows(IOException::class.java) { stream.readFrame() }
    }

    private fun feed(data: ByteArray) = assembler.onPayload(data, 0, data.size)

    /** 每次 read 吐一批；没有了就当这一下超时，把假时钟往前拨。 */
    private class ScriptedReader(vararg batches: List<ByteArray>) : PayloadReader {
        private val queue = ArrayDeque(batches.toList())
        private var time = 0L
        var closed = false

        fun now() = time

        override fun read(timeoutMs: Int, onPayload: (ByteArray, Int, Int) -> Unit) {
            val batch = queue.removeFirstOrNull()
            if (batch.isNullOrEmpty()) time += timeoutMs
            batch?.forEach { onPayload(it, 0, it.size) }
        }

        override fun close() {
            closed = true
        }
    }
}

private fun payload(fid: Int, vararg data: Int, eof: Boolean = false, error: Boolean = false): ByteArray {
    var info = 0x80 or fid
    if (eof) info = info or 0x02
    if (error) info = info or 0x40
    return bytes(0x02, info, *data)
}

private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
