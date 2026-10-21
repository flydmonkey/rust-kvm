package com.rustkvm.client.hid

import kotlinx.coroutines.delay

data class PasteResult(
    val typed: Int,
    val skipped: Int,
)

class PasteTyper(
    private val ch9329: Ch9329,
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    /** [modifierBits] 加在每个字符上，用于软键盘配合粘滞的 Ctrl / Alt 打出组合键。 */
    suspend fun typeText(text: String, modifierBits: Int = 0): PasteResult {
        var typed = 0
        var skipped = 0
        text.forEach { character ->
            val key = resolve(character)
            if (key == null) {
                skipped += 1
            } else {
                val shift = if (key.shifted) Ch9329.LEFT_SHIFT else 0
                ch9329.pressUsage(key.code, modifierBits or shift)
                wait(PACKET_GAP_MS)
                ch9329.releaseAllKeys()
                wait(PACKET_GAP_MS)
                typed += 1
            }
        }
        return PasteResult(typed, skipped)
    }

    private fun resolve(character: Char): TypeKey? = when {
        character == '\n' || character == '\r' -> TypeKey(0x28)
        character == '\t' -> TypeKey(0x2B)
        character == ' ' -> TypeKey(0x2C)
        character in 'A'..'Z' -> TypeKey(0x04 + character.code - 'A'.code, shifted = true)
        character in 'a'..'z' -> TypeKey(0x04 + character.code - 'a'.code)
        character in '1'..'9' -> TypeKey(0x1E + character.code - '1'.code)
        character == '0' -> TypeKey(0x27)
        character in SHIFTED_KEYS -> TypeKey(SHIFTED_KEYS.getValue(character), shifted = true)
        character in UNSHIFTED_KEYS -> TypeKey(UNSHIFTED_KEYS.getValue(character))
        else -> null
    }

    private data class TypeKey(val code: Int, val shifted: Boolean = false)

    companion object {
        // Mirrors web input paste pacing: each key press/release packet is separated by 20 ms.
        const val PACKET_GAP_MS = 20L

        private val SHIFTED_KEYS = mapOf(
            '!' to 0x1E, '@' to 0x1F, '#' to 0x20, '$' to 0x21, '%' to 0x22,
            '^' to 0x23, '&' to 0x24, '*' to 0x25, '(' to 0x26, ')' to 0x27,
            '_' to 0x2D, '+' to 0x2E, '{' to 0x2F, '}' to 0x30, '|' to 0x31,
            ':' to 0x33, '"' to 0x34, '~' to 0x35, '<' to 0x36, '>' to 0x37,
            '?' to 0x38,
        )
        private val UNSHIFTED_KEYS = mapOf(
            '-' to 0x2D, '=' to 0x2E, '[' to 0x2F, ']' to 0x30, '\\' to 0x31,
            ';' to 0x33, '\'' to 0x34, '`' to 0x35, ',' to 0x36, '.' to 0x37,
            '/' to 0x38,
        )
    }
}
