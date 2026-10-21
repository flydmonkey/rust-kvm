package com.rustkvm.client.hid

import com.rustkvm.protocol.SerialSettings
import com.rustkvm.protocol.SerialText
import com.rustkvm.protocol.parseSerialText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.IOException

interface SerialConnection : Closeable {
    val serialText: Flow<String>
    val serialBinary: Flow<ByteArray>

    suspend fun connect()
    fun sendSerialText(text: String): Boolean
    fun sendSerialBinary(data: ByteArray): Boolean
}

class ControlBusy(message: String) : IOException(message)
class SerialControlFailure(message: String) : IOException(message)

class SerialControl<S : SerialConnection>(
    private val sessionFactory: (takeover: Boolean) -> S,
    private val settings: SerialSettings,
    private val onBusy: (ControlBusy) -> Unit = {},
    private val onInputFailure: (Throwable) -> Unit = {},
) : Closeable {
    private var connection: S? = null
    private var ch9329: Ch9329? = null
    private var pasteTyper: PasteTyper? = null
    private var absoluteMouseOverride: Boolean? = null
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var hidWriter: HidWriteQueue? = null

    suspend fun open(takeover: Boolean = false): S {
        check(connection == null) { "Serial control is already open" }
        val candidate = sessionFactory(takeover)
        try {
            candidate.connect()
            if (!candidate.sendSerialText(openRequest(settings))) {
                throw IOException("串口打开请求发送失败")
            }

            when (
                val response = candidate.serialText
                    .map(::parseSerialText)
                    .first { it is SerialText.Error || it is SerialText.Status && it.open }
            ) {
                is SerialText.Error -> {
                    if (response.code == "busy") {
                        throw ControlBusy(response.message).also(onBusy)
                    }
                    throw IOException(response.message)
                }
                is SerialText.Status -> Unit
                else -> error("Unexpected serial response")
            }

            connection = candidate
            val queuedWriter = HidWriteQueue(
                scope = writeScope,
                write = { frame ->
                    if (!candidate.sendSerialBinary(frame)) {
                        throw IOException("串口输入发送失败")
                    }
                },
                onFailure = onInputFailure,
            )
            hidWriter = queuedWriter
            val hid = Ch9329(
                absoluteMouse = absoluteMouseOverride ?: (settings.mouseMode != "relative"),
                write = queuedWriter::offer,
                writeMove = queuedWriter::offerMove,
            )
            ch9329 = hid
            pasteTyper = PasteTyper(hid)
            return candidate
        } catch (error: Throwable) {
            candidate.close()
            throw error
        }
    }

    fun keydown(key: String) = requireHid().keydown(key)

    fun keyup(key: String) = requireHid().keyup(key)

    fun releaseAllKeys() = requireHid().releaseAllKeys()

    fun ctrlAltDelete() = requireHid().ctrlAltDelete()

    suspend fun pasteText(text: String): PasteResult =
        checkNotNull(pasteTyper) { "Serial control is not open" }.typeText(text)

    suspend fun monitorDownlink(): Nothing = coroutineScope {
        val activeConnection = checkNotNull(connection) { "Serial control is not open" }
        launch {
            activeConnection.serialBinary.collect { /* Drain CH9329 acknowledgements. */ }
        }
        activeConnection.serialText.collect { text ->
            when (val message = parseSerialText(text)) {
                is SerialText.Error -> {
                    if (message.code == "busy") {
                        throw ControlBusy(message.message).also(onBusy)
                    }
                    throw SerialControlFailure(message.message)
                }
                is SerialText.Status -> if (!message.open) {
                    throw SerialControlFailure("串口连接已关闭")
                }
                SerialText.Pong, SerialText.Unknown -> Unit
            }
        }
        throw SerialControlFailure("串口状态流已结束")
    }

    fun mouseMoveAbsolute(nx: Float, ny: Float, buttons: Int) =
        requireHid().mouseMoveAbsolute(nx, ny, buttons)

    fun mouseMoveRelative(dx: Int, dy: Int, buttons: Int) =
        requireHid().mouseMoveRelative(dx, dy, buttons)

    fun mouseScroll(notches: Int) = requireHid().mouseScroll(notches)

    fun pressKey(key: String) {
        val hid = requireHid()
        hid.keydown(key)
        hid.keyup(key)
    }

    fun setAbsoluteMouse(absolute: Boolean) {
        absoluteMouseOverride = absolute
        ch9329?.absoluteMouse = absolute
    }

    val absoluteMouse: Boolean
        get() = ch9329?.absoluteMouse
            ?: absoluteMouseOverride
            ?: (settings.mouseMode != "relative")

    override fun close() {
        pasteTyper = null
        ch9329 = null
        hidWriter?.close()
        hidWriter = null
        writeScope.cancel()
        connection?.close()
        connection = null
    }

    private fun requireHid(): Ch9329 =
        checkNotNull(ch9329) { "Serial control is not open" }
}

internal class HidWriteQueue(
    scope: CoroutineScope,
    private val write: (ByteArray) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) : Closeable {
    private val jobs = Channel<HidWrite>(Channel.UNLIMITED)
    private val moveLock = Any()
    private var pendingMove: ByteArray? = null
    private var moveQueued = false
    private val worker = scope.launch {
        for (job in jobs) {
            val frame = when (job) {
                is HidWrite.Command -> job.frame
                HidWrite.PendingMove -> synchronized(moveLock) {
                    moveQueued = false
                    pendingMove.also { pendingMove = null }
                }
            } ?: continue
            runCatching {
                write(frame)
                delay(PACKET_GAP_MS)
            }
                .onFailure {
                    onFailure(it)
                    jobs.cancel()
                }
        }
    }

    fun offer(frame: ByteArray) {
        jobs.trySend(HidWrite.Command(frame))
    }

    fun offerMove(frame: ByteArray) {
        synchronized(moveLock) {
            pendingMove = frame
            if (!moveQueued) {
                moveQueued = true
                jobs.trySend(HidWrite.PendingMove)
            }
        }
    }

    override fun close() {
        jobs.close()
        worker.cancel()
    }

    private sealed interface HidWrite {
        data class Command(val frame: ByteArray) : HidWrite
        data object PendingMove : HidWrite
    }

    private companion object {
        const val PACKET_GAP_MS = 4L
    }
}

private fun openRequest(settings: SerialSettings): String =
    """{"op":"open","port":"${settings.port.escapeJson()}","baudRate":${settings.baudRate}}"""

private fun String.escapeJson(): String = buildString(length) {
    for (character in this@escapeJson) {
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character < ' ') {
                append("\\u")
                append(character.code.toString(16).padStart(4, '0'))
            } else {
                append(character)
            }
        }
    }
}
