package com.rustkvm.client.net

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class StreamMessageTest {
    @Test
    fun receiveBatchTakesEverythingAlreadyQueued() = runBlocking {
        val channel = Channel<Int>(Channel.UNLIMITED)
        (1..5).forEach { channel.trySend(it) }

        assertEquals(listOf(1, 2, 3, 4, 5), channel.receiveBatch())
    }

    @Test
    fun receiveBatchReturnsNullWhenClosedNormally() = runBlocking {
        val channel = Channel<Int>(Channel.UNLIMITED)
        channel.close()

        assertNull(channel.receiveBatch())
    }

    @Test
    fun receiveBatchDeliversQueuedItemsBeforeReportingClose() = runBlocking {
        val channel = Channel<Int>(Channel.UNLIMITED)
        channel.trySend(1)
        channel.close()

        assertEquals(listOf(1), channel.receiveBatch())
        assertNull(channel.receiveBatch())
    }

    @Test
    fun receiveBatchRethrowsCloseCause() = runBlocking {
        val channel = Channel<Int>(Channel.UNLIMITED)
        val cause = IOException("video closed")
        channel.close(cause)

        try {
            channel.receiveBatch()
            fail("expected close cause")
        } catch (error: IOException) {
            assertSame(cause, error)
        }
    }
}
