package com.rustkvm.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoubleBackPressTest {
    @Test
    fun secondPressWithinWindowConfirms() {
        val gate = DoubleBackPress(windowMs = 2_000)
        assertFalse(gate.press(10_000))
        assertTrue(gate.press(11_500))
    }

    @Test
    fun secondPressAfterWindowStartsOver() {
        val gate = DoubleBackPress(windowMs = 2_000)
        assertFalse(gate.press(10_000))
        assertFalse(gate.press(12_500))
        assertTrue(gate.press(13_000))
    }

    @Test
    fun confirmationResetsTheGate() {
        val gate = DoubleBackPress(windowMs = 2_000)
        gate.press(0)
        assertTrue(gate.press(100))
        assertFalse(gate.press(200))
    }
}
