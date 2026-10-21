package com.rustkvm.app.ui.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteKeyboardTest {
    @Test
    fun typedTextIsInserted() {
        assertEquals(KeyboardEdit(0, "a"), diffKeyboardInput("____a", sentinel = "____"))
    }

    @Test
    fun backspaceDeletesFromSentinel() {
        assertEquals(KeyboardEdit(2, ""), diffKeyboardInput("__", sentinel = "____"))
    }

    @Test
    fun replacementCountsDeletesThenInsert() {
        assertEquals(KeyboardEdit(1, "xy"), diffKeyboardInput("___xy", sentinel = "____"))
    }

    @Test
    fun newlineIsPassedThroughForEnter() {
        assertEquals(KeyboardEdit(0, "\n"), diffKeyboardInput("____\n", sentinel = "____"))
    }

    @Test
    fun sentinelsAlternateSoEveryResetIsANewValue() {
        val other = nextKeyboardSentinel(KEYBOARD_SENTINEL)

        assertTrue(other != KEYBOARD_SENTINEL)
        assertEquals(KEYBOARD_SENTINEL.length, other.length)
        assertEquals(KEYBOARD_SENTINEL, nextKeyboardSentinel(other))
    }

    @Test
    fun unchangedTextIsEmpty() {
        assertTrue(diffKeyboardInput("____", sentinel = "____").isEmpty)
    }
}
