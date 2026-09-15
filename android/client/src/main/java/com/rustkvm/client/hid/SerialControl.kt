package com.rustkvm.client.hid

import com.rustkvm.protocol.SerialSettings
import com.rustkvm.protocol.SerialText
import com.rustkvm.protocol.parseSerialText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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

class SerialControl<S : SerialConnection>(
    private val sessionFactory: (takeover: Boolean) -> S,
    private val settings: SerialSettings,
    private val onBusy: (ControlBusy) -> Unit = {},
) : Closeable {
    private var connection: S? = null
    private var ch9329: Ch9329? = null
    private var pasteTyper: PasteTyper? = null
    private var absoluteMouseOverride: Boolean? = null

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
            val hid = Ch9329(
                absoluteMouse = absoluteMouseOverride ?: (settings.mouseMode != "relative"),
                write = { frame ->
                    if (!candidate.sendSerialBinary(frame)) {
                        throw IOException("串口输入发送失败")
                    }
                },
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

    fun mouseMoveAbsolute(nx: Float, ny: Float, buttons: Int) =
        requireHid().mouseMoveAbsolute(nx, ny, buttons)

    fun mouseMoveRelative(dx: Int, dy: Int, buttons: Int) =
        requireHid().mouseMoveRelative(dx, dy, buttons)

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
        connection?.close()
        connection = null
    }

    private fun requireHid(): Ch9329 =
        checkNotNull(ch9329) { "Serial control is not open" }
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
