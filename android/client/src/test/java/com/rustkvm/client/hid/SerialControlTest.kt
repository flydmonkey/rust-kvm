package com.rustkvm.client.hid

import com.rustkvm.protocol.SerialSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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

        assertArrayEquals(
            bytes(0x57, 0xAB, 0x00, 0x04, 0x07, 0x02, 0x01, 0xFF, 0x0F, 0, 0, 0, 0x1E),
            session.sentBinary.single(),
        )
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}

private class FakeSerialConnection : SerialConnection {
    val text = MutableSharedFlow<String>(replay = 1)
    override val serialText: Flow<String> = text
    override val serialBinary: Flow<ByteArray> = MutableSharedFlow()
    val sentText = mutableListOf<String>()
    val sentBinary = mutableListOf<ByteArray>()
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
