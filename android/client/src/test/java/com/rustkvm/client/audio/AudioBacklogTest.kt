package com.rustkvm.client.audio

import com.rustkvm.client.net.StreamMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AudioBacklogTest {
    private fun chunk(id: Int) = StreamMessage.Binary(byteArrayOf(id.toByte()))

    private fun ids(batch: List<StreamMessage>): List<String> = batch.map {
        when (it) {
            is StreamMessage.Text -> it.text
            is StreamMessage.Binary -> it.data[0].toString()
        }
    }

    @Test
    fun keepsBatchUntouchedAtOrBelowLimit() {
        val batch = (1..4).map(::chunk)

        assertSame(batch, trimAudioBacklog(batch, maxChunks = 4, keepChunks = 2))
    }

    @Test
    fun dropsOldestChunksDownToKeep() {
        val batch = (1..6).map(::chunk)

        assertEquals(listOf("5", "6"), ids(trimAudioBacklog(batch, maxChunks = 4, keepChunks = 2)))
    }

    @Test
    fun keepsFormatHeadersInOrder() {
        val batch = listOf(chunk(1), chunk(2), StreamMessage.Text("fmt"), chunk(3), chunk(4), chunk(5))

        assertEquals(
            listOf("fmt", "4", "5"),
            ids(trimAudioBacklog(batch, maxChunks = 4, keepChunks = 2)),
        )
    }
}
