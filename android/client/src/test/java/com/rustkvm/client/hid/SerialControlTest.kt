package com.rustkvm.client.hid

import com.rustkvm.protocol.SerialSettings
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SerialControlTest {
    @Test
    fun openConnectsRequestedSessionAndWaitsForOpenStatus() = runBlocking {
        val session = FakeSerialConnection()
        val control = SerialControl(
            sessionFactory = { takeover ->
                assertTrue(takeover)
                session
            },
            settings = SerialSettings(port = "COM3", baudRate = 57_600),
        )

        session.text.emit("""{"op":"status","open":true,"baudRate":57600}""")
        val opened = control.open(takeover = true)

        assertEquals(session, opened)
        assertTrue(session.connected)
        assertEquals("""{"op":"open","port":"COM3","baudRate":57600}""", session.sentText.single())
    }

    @Test
    fun busyErrorIsReportedAndClosesSession() = runBlocking {
        val session = FakeSerialConnection()
        var busy: ControlBusy? = null
        val control = SerialControl(
            sessionFactory = { session },
            settings = SerialSettings(port = "COM4"),
            onBusy = { busy = it },
        )

        session.text.emit("""{"op":"error","code":"busy","message":"owned"}""")
        val error = runCatching { control.open() }.exceptionOrNull()

        assertTrue(error is ControlBusy)
        assertEquals("owned", busy?.message)
        assertTrue(session.closed)
    }

    @Test
    fun setAbsoluteMouseUpdatesCh9329Mode() = runBlocking {
        val session = FakeSerialConnection()
        val control = SerialControl(
            sessionFactory = { session },
            settings = SerialSettings(mouseMode = "absolute"),
        )
        session.text.emit("""{"op":"status","open":true}""")
        control.open()
        control.setAbsoluteMouse(false)
        assertEquals(false, control.absoluteMouse)
    }

    @Test
    fun absoluteMouseWritesCh9329FrameToSerialBinary() = runBlocking {
        val session = FakeSerialConnection()
        val control = SerialControl(
            sessionFactory = { session },
            settings = SerialSettings(mouseMode = "absolute"),
        )
        session.text.emit("""{"op":"status","open":true}""")
        control.open()

        control.mouseMoveAbsolute(1f, 0f, buttons = 1)
        withTimeout(1_000) {
            while (session.sentBinary.isEmpty()) delay(1)
        }

        assertArrayEquals(
            bytes(0x57, 0xAB, 0x00, 0x04, 0x07, 0x02, 0x01, 0xFF, 0x0F, 0, 0, 0, 0x1E),
            session.sentBinary.single(),
        )
    }

    @Test
    fun monitorDownlinkFailsWhenSerialClosesAfterOpen() = runBlocking {
        val session = FakeSerialConnection()
        val control = SerialControl(
            sessionFactory = { session },
            settings = SerialSettings(),
        )
        session.text.emit("""{"op":"status","open":true}""")
        control.open()

        launch {
            delay(1)
            session.text.emit("""{"op":"status","open":false}""")
        }
        val error = runCatching { withTimeout(1_000) { control.monitorDownlink() } }.exceptionOrNull()

        assertTrue(error is SerialControlFailure)
        assertEquals("串口连接已关闭", error?.message)
    }

    @Test
    fun monitorDownlinkReportsBusyAfterOpen() = runBlocking {
        val session = FakeSerialConnection()
        var busy: ControlBusy? = null
        val control = SerialControl(
            sessionFactory = { session },
            settings = SerialSettings(),
            onBusy = { busy = it },
        )
        session.text.emit("""{"op":"status","open":true}""")
        control.open()

        launch {
            delay(1)
            session.text.emit("""{"op":"error","code":"busy","message":"taken"}""")
        }
        val error = runCatching { withTimeout(1_000) { control.monitorDownlink() } }.exceptionOrNull()

        assertTrue(error is ControlBusy)
        assertEquals("taken", busy?.message)
    }

    @Test
    fun monitorDownlinkDrainsSerialBinary() = runBlocking {
        val session = FakeSerialConnection()
        val control = SerialControl(
            sessionFactory = { session },
            settings = SerialSettings(),
        )
        session.text.emit("""{"op":"status","open":true}""")
        control.open()

        val monitored = launch { control.monitorDownlink() }
        withTimeout(1_000) {
            while (session.binary.subscriptionCount.value == 0) delay(1)
        }

        assertTrue(session.binary.subscriptionCount.value > 0)
        monitored.cancel()
    }

    @Test
    fun moveWriterKeepsOnlyLatestPendingFrame() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val written = mutableListOf<Int>()
        val writer = HidWriteQueue(
            scope = scope,
            write = { frame ->
                if (frame[0].toInt() == 1) {
                    firstStarted.countDown()
                    releaseFirst.await(1, TimeUnit.SECONDS)
                }
                synchronized(written) { written += frame[0].toInt() }
            },
            onFailure = { throw AssertionError(it) },
        )

        writer.offerMove(byteArrayOf(1))
        assertTrue(firstStarted.await(1, TimeUnit.SECONDS))
        writer.offerMove(byteArrayOf(2))
        writer.offerMove(byteArrayOf(3))
        releaseFirst.countDown()
        withTimeout(1_000) {
            while (synchronized(written) { 3 !in written }) delay(1)
        }

        assertFalse(synchronized(written) { 2 in written })
        writer.close()
        scope.cancel()
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}

private class FakeSerialConnection : SerialConnection {
    val text = MutableSharedFlow<String>(replay = 1)
    override val serialText: Flow<String> = text
    val binary = MutableSharedFlow<ByteArray>()
    override val serialBinary: Flow<ByteArray> = binary
    val sentText = mutableListOf<String>()
    val sentBinary = CopyOnWriteArrayList<ByteArray>()
    var connected = false
    var closed = false

    override suspend fun connect() {
        connected = true
    }

    override fun sendSerialText(text: String): Boolean {
        sentText += text
        return true
    }

    override fun sendSerialBinary(data: ByteArray): Boolean {
        sentBinary += data
        return true
    }

    override fun close() {
        closed = true
    }
}
