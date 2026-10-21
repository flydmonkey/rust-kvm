package com.rustkvm.app.ui.input

import org.junit.Assert.assertEquals
import org.junit.Test

class ModifierLatchTest {
    @Test
    fun firstTapLatches() {
        assertEquals(
            ModifierTapResult(latched = setOf("ControlLeft", "AltLeft")),
            onModifierTap(setOf("ControlLeft"), "AltLeft"),
        )
    }

    @Test
    fun tappingLatchedModifierSendsItAloneAndClears() {
        assertEquals(
            ModifierTapResult(latched = emptySet(), sendKey = "MetaLeft"),
            onModifierTap(setOf("MetaLeft"), "MetaLeft"),
        )
    }

    @Test
    fun tappingLatchedModifierKeepsOtherLatchedOnesInTheChord() {
        assertEquals(
            ModifierTapResult(
                latched = emptySet(),
                sendKey = "ShiftLeft",
                sendModifiers = setOf("ControlLeft"),
            ),
            onModifierTap(setOf("ControlLeft", "ShiftLeft"), "ShiftLeft"),
        )
    }
}
