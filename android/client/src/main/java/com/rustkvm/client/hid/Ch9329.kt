package com.rustkvm.client.hid

import kotlin.math.floor

/**
 * Phase-one CH9329 keyboard and mouse packet encoder.
 *
 * Writes are immediate; ACK queueing can be added without changing the public input API.
 */
class Ch9329(
    val absoluteMouse: Boolean,
    private val write: (ByteArray) -> Unit,
) {
    private val pressedKeys = mutableListOf<Int>()
    private val pressedModifiers = mutableSetOf<String>()

    fun keydown(key: String) {
        val modifier = Ch9329Maps.CONTROL_KEY_BITS[key]
        if (modifier != null) {
            pressedModifiers += key
            syncKeyboard()
            return
        }

        val keyCode = Ch9329Maps.KEYBOARD_MAP[key] ?: return
        if (keyCode !in pressedKeys) {
            if (pressedKeys.size >= MAX_KEYS) pressedKeys.removeAt(0)
            pressedKeys += keyCode
        }
        syncKeyboard()
    }

    fun keyup(key: String) {
        if (key in Ch9329Maps.CONTROL_KEY_BITS) {
            pressedModifiers -= key
            syncKeyboard()
            return
        }

        Ch9329Maps.KEYBOARD_MAP[key]?.let(pressedKeys::remove)
        syncKeyboard()
    }

    fun releaseAllKeys() {
        pressedKeys.clear()
        pressedModifiers.clear()
        write(KEYBOARD_RELEASE_PACKET.copyOf())
    }

    fun mouseMoveAbsolute(nx: Float, ny: Float, buttons: Int) {
        val x = absoluteCoordinate(nx)
        val y = absoluteCoordinate(ny)
        write(
            packet(
                command = COMMAND_MOUSE_ABSOLUTE,
                data = intArrayOf(
                    0x02,
                    buttons and 0xFF,
                    x and 0xFF,
                    (x ushr 8) and 0xFF,
                    y and 0xFF,
                    (y ushr 8) and 0xFF,
                    0x00,
                ),
            ),
        )
    }

    fun mouseMoveRelative(dx: Int, dy: Int, buttons: Int) {
        write(
            packet(
                command = COMMAND_MOUSE_RELATIVE,
                data = intArrayOf(
                    0x01,
                    buttons and 0xFF,
                    dx.coerceIn(-127, 127) and 0xFF,
                    dy.coerceIn(-127, 127) and 0xFF,
                    0x00,
                ),
            ),
        )
    }

    fun ctrlAltDelete() {
        write(
            packet(
                command = COMMAND_KEYBOARD,
                data = intArrayOf(0x05, 0x00, 0x4C, 0x00, 0x00, 0x00, 0x00, 0x00),
            ),
        )
        releaseAllKeys()
    }

    private fun syncKeyboard() {
        val data = IntArray(8)
        data[0] = pressedModifiers.fold(0) { bits, key ->
            bits or Ch9329Maps.CONTROL_KEY_BITS.getValue(key)
        }
        pressedKeys.take(MAX_KEYS).forEachIndexed { index, keyCode ->
            data[index + 2] = keyCode
        }
        write(packet(COMMAND_KEYBOARD, data))
    }

    private fun packet(command: Int, data: IntArray): ByteArray {
        val frame = IntArray(HEADER_SIZE + data.size)
        frame[0] = 0x57
        frame[1] = 0xAB
        frame[2] = ADDRESS
        frame[3] = command
        frame[4] = data.size
        data.copyInto(frame, destinationOffset = HEADER_SIZE)

        val checksum = frame.fold(0) { sum, value -> (sum + value) and 0xFF }
        return ByteArray(frame.size + 1) { index ->
            (if (index < frame.size) frame[index] else checksum).toByte()
        }
    }

    private fun absoluteCoordinate(normalized: Float): Int =
        floor(normalized.coerceIn(0f, 1f) * 4096f).toInt().coerceIn(0, 4095)

    companion object {
        private const val ADDRESS = 0x00
        private const val COMMAND_KEYBOARD = 0x02
        private const val COMMAND_MOUSE_ABSOLUTE = 0x04
        private const val COMMAND_MOUSE_RELATIVE = 0x05
        private const val HEADER_SIZE = 5
        private const val MAX_KEYS = 6

        private val KEYBOARD_RELEASE_PACKET = byteArrayOf(
            0x57, 0xAB.toByte(), 0x00, 0x02, 0x08,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x0C,
        )
    }
}
