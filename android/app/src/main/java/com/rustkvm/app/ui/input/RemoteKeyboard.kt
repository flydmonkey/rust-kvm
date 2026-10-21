package com.rustkvm.app.ui.input

/**
 * 软键盘输入的落点：一个看不见的输入框，里面常驻 [KEYBOARD_SENTINEL]，每次变化后复位。
 *
 * 输入框空着的时候输入法发不出退格（没东西可删），所以要垫一段占位；占位的长度就是
 * 一次回调里能识别的最多退格数。
 *
 * 复位时在两段占位之间交替：复位成和上次相同的值不会触发重组，输入法那边的缓冲就
 * 不会跟着复位，下一次输入会把上次的字符再算一遍。
 */
const val KEYBOARD_SENTINEL = "________"
private const val KEYBOARD_SENTINEL_ALT = "--------"

fun nextKeyboardSentinel(current: String): String =
    if (current == KEYBOARD_SENTINEL) KEYBOARD_SENTINEL_ALT else KEYBOARD_SENTINEL

data class KeyboardEdit(val deleted: Int, val inserted: String) {
    val isEmpty: Boolean
        get() = deleted == 0 && inserted.isEmpty()
}

fun diffKeyboardInput(text: String, sentinel: String = KEYBOARD_SENTINEL): KeyboardEdit {
    val common = sentinel.commonPrefixWith(text).length
    return KeyboardEdit(
        deleted = sentinel.length - common,
        inserted = text.substring(common),
    )
}
