package com.rustkvm.client.hid

/**
 * Browser [KeyboardEvent.code] names mapped to USB HID keyboard usage IDs.
 *
 * Values mirror the table used by web/ch9329.js.
 */
object Ch9329Maps {
    val KEYBOARD_MAP: Map<String, Int> = buildMap {
        putAll(
            mapOf(
                "Backspace" to 0x2A, "Back" to 0x2A, "Tab" to 0x2B,
                "Enter" to 0x28, "Pause" to 0x48, "CapsLock" to 0x39,
                "Escape" to 0x29, "Space" to 0x2C, " " to 0x2C,
                "PageUp" to 0x4B, "PageDown" to 0x4E, "Next" to 0x4E,
                "End" to 0x4D, "Home" to 0x4A,
                "ArrowLeft" to 0x50, "Left" to 0x50,
                "ArrowUp" to 0x52, "Up" to 0x52,
                "ArrowRight" to 0x4F, "Right" to 0x4F,
                "ArrowDown" to 0x51, "Down" to 0x51,
                "PrintScreen" to 0x46, "Insert" to 0x49, "Delete" to 0x4C,
                "ContextMenu" to 0x65, "NumLock" to 0x53,
                "NumpadMultiply" to 0x55, "NumpadAdd" to 0x57,
                "NumpadSubtract" to 0x56, "NumpadDecimal" to 0x63,
                "NumpadDivide" to 0x54,
                "Semicolon" to 0x33, ";" to 0x33,
                "Equal" to 0x2E, "=" to 0x2E,
                "Comma" to 0x36, "," to 0x36,
                "Minus" to 0x2D, "-" to 0x2D,
                "Period" to 0x37, "." to 0x37,
                "Slash" to 0x38, "/" to 0x38,
                "Backquote" to 0x35, "`" to 0x35,
                "BracketLeft" to 0x2F, "[" to 0x2F,
                "Backslash" to 0x31, "\\" to 0x31,
                "BracketRight" to 0x30, "]" to 0x30,
                "Quote" to 0x34, "'" to 0x34,
                "ShiftLeft" to 0xE1, "ShiftRight" to 0xE5, "Shift" to 0xE1,
                "ControlLeft" to 0xE0, "ControlRight" to 0xE4, "Control" to 0xE0,
                "AltLeft" to 0xE2, "AltRight" to 0xE6, "Alt" to 0xE2,
                "MetaLeft" to 0xE3, "MetaRight" to 0xE7, "Meta" to 0xE3,
            ),
        )
        for (index in 0..25) {
            val letter = ('A'.code + index).toChar()
            put("Key$letter", 0x04 + index)
            put(letter.lowercase(), 0x04 + index)
        }
        val digitUsages = intArrayOf(0x27, 0x1E, 0x1F, 0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26)
        for (digit in 0..9) {
            put("Digit$digit", digitUsages[digit])
            put(digit.toString(), digitUsages[digit])
        }
        val numpadUsages = intArrayOf(0x62, 0x59, 0x5A, 0x5B, 0x5C, 0x5D, 0x5E, 0x5F, 0x60, 0x61)
        for (digit in 0..9) put("Numpad$digit", numpadUsages[digit])
        for (number in 1..12) put("F$number", 0x39 + number)
    }

    val CONTROL_KEY_BITS: Map<String, Int> = mapOf(
        "ShiftLeft" to 0x02,
        "ShiftRight" to 0x20,
        "ControlLeft" to 0x01,
        "ControlRight" to 0x10,
        "AltLeft" to 0x04,
        "AltRight" to 0x40,
        "MetaLeft" to 0x08,
        "MetaRight" to 0x80,
        // 不分左右的别名按左侧处理，否则会被当成普通键塞进按键槽
        "Shift" to 0x02,
        "Control" to 0x01,
        "Alt" to 0x04,
        "Meta" to 0x08,
    )
}
