package com.rustkvm.client.hid

import kotlin.math.floor

/**
 * Phase-one CH9329 keyboard and mouse packet encoder.
 *
 * Writes are immediate; ACK queueing can be added without changing the public input API.
 */
class Ch9329(
    absoluteMouse: Boolean,
    private val writeMove: ((ByteArray) -> Unit)? = null,
    private val write: (ByteArray) -> Unit,
) {
    var absoluteMouse: Boolean = absoluteMouse
    private val pressedKeys = mutableListOf<Int>()
    private val pressedModifiers = mutableSetOf<String>()
    private var mouseButtons = 0
    private var lastAbsoluteX = 0
    private var lastAbsoluteY = 0

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
        lastAbsoluteX = absoluteCoordinate(nx)
        lastAbsoluteY = absoluteCoordinate(ny)
        mouseWriter(buttons)(absolutePacket(buttons, wheel = 0))
    }

    fun mouseMoveRelative(dx: Int, dy: Int, buttons: Int) {
        mouseWriter(buttons)(relativePacket(buttons, dx, dy, wheel = 0))
    }

    /**
     * 正数向上滚。绝对模式的滚轮包也带坐标，沿用最后一次的位置，否则光标会跳到左上角。
     * 每一格都是独立事件，走不可合并的命令队列。
     */
    fun mouseScroll(notches: Int) {
        if (notches == 0) return
        val wheel = notches.coerceIn(-127, 127)
        write(
            if (absoluteMouse) {
                absolutePacket(mouseButtons, wheel)
            } else {
                relativePacket(mouseButtons, 0, 0, wheel)
            },
        )
    }

    private fun absolutePacket(buttons: Int, wheel: Int): ByteArray = packet(
        command = COMMAND_MOUSE_ABSOLUTE,
        data = intArrayOf(
            0x02,
            buttons and 0xFF,
            lastAbsoluteX and 0xFF,
            (lastAbsoluteX ushr 8) and 0xFF,
            lastAbsoluteY and 0xFF,
            (lastAbsoluteY ushr 8) and 0xFF,
            wheel and 0xFF,
        ),
    )

    private fun relativePacket(buttons: Int, dx: Int, dy: Int, wheel: Int): ByteArray = packet(
        command = COMMAND_MOUSE_RELATIVE,
        data = intArrayOf(
            0x01,
            buttons and 0xFF,
            dx.coerceIn(-127, 127) and 0xFF,
            dy.coerceIn(-127, 127) and 0xFF,
            wheel and 0xFF,
        ),
    )

    fun ctrlAltDelete() {
        write(
            packet(
                command = COMMAND_KEYBOARD,
                data = intArrayOf(0x05, 0x00, 0x4C, 0x00, 0x00, 0x00, 0x00, 0x00),
            ),
        )
        releaseAllKeys()
    }

    /**
     * 一次性组合键：带上 [modifiers] 按下再松开 [key]，之后恢复外接键盘当前按住的状态。
     * [key] 本身是修饰键时只发修饰位（单按 Win 打开开始菜单就是这样）。
     */
    fun tap(key: String, modifiers: Set<String> = emptySet()) {
        val ownBit = Ch9329Maps.CONTROL_KEY_BITS[key]
        val usage = if (ownBit != null) 0 else Ch9329Maps.KEYBOARD_MAP[key] ?: return
        pressUsage(usage, modifierBits(modifiers) or (ownBit ?: 0))
        syncKeyboard()
    }

    internal fun pressUsage(code: Int, modifierBits: Int) {
        write(
            packet(
                command = COMMAND_KEYBOARD,
                data = intArrayOf(modifierBits and 0xFF, 0x00, code, 0x00, 0x00, 0x00, 0x00, 0x00),
            ),
        )
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

    private fun mouseWriter(buttons: Int): (ByteArray) -> Unit =
        if (buttons != mouseButtons) {
            mouseButtons = buttons
            write
        } else {
            writeMove ?: write
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
        fun modifierBits(modifiers: Set<String>): Int =
            modifiers.fold(0) { bits, key -> bits or (Ch9329Maps.CONTROL_KEY_BITS[key] ?: 0) }

        internal const val LEFT_SHIFT = 0x02
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
