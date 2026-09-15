package com.rustkvm.client.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmPlayerTest {
    @Test
    fun bufferSizeCoversThreeTenMillisecondStereoChunks() {
        val size = pcmBufferSize(
            sampleRate = 48_000,
            channels = 2,
            minimumBufferSize = 1,
        )

        assertEquals(5_760, size)
        assertTrue(size > 0)
    }

    @Test
    fun bufferSizeNeverFallsBelowPlatformMinimum() {
        val size = pcmBufferSize(
            sampleRate = 24_000,
            channels = 1,
            minimumBufferSize = 4_096,
        )

        assertEquals(4_096, size)
    }
}
