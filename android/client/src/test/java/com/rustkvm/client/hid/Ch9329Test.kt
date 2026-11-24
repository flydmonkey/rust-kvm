package com.rustkvm.client.hid

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Ch9329Test {
    @Test
    fun absoluteCoordsClamp() {
        val frames = mutableListOf<ByteArray>()
        val ch = Ch9329(absoluteMouse = true) { frames += it }

        ch.mouseMoveAbsolute(1f, 0f, 0)

        assertArrayEquals(
            bytes(0x57, 0xAB, 0x00, 0x04, 0x07, 0x02, 0x00, 0xFF, 0x0F, 0x00, 0x00, 0x00, 0x1D),
            frames.single(),
        )
    }

    @Test
    fun tapSendsChordThenRestoresHeldKeys() {
        val frames = mutableListOf<ByteArray>()
        val ch = Ch9329(absoluteMouse = true) { frames += it }

        ch.tap("KeyC", setOf("ControlLeft"))

        assertArrayEquals(
            bytes(0x57, 0xAB, 0x00, 0x02, 0x08, 0x01, 0x00, 0x06, 0x00, 0x00, 0x00, 0x00, 0x00, 0x13),
            frames[0],
        )
        assertArrayEquals(
            bytes(0x57, 0xAB, 0x00, 0x02, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0C),
            frames[1],
        )
        assertEquals(2, frames.size)
    }

    @Test
    fun genericModifierNamesSetModifierBitsInsteadOfKeySlots() {
        val frames = mutableListOf<ByteArray>()
        val ch = Ch9329(absoluteMouse = true) { frames += it }

        ch.keydown("Control")

        assertEquals(0x01, frames.last()[5].toInt())
        assertEquals(0x00, frames.last()[7].toInt())
    }

    @Test
    fun tappingAModifierAloneSendsOnlyItsBit() {
        val frames = mutableListOf<ByteArray>()
        val ch = Ch9329(absoluteMouse = true) { frames += it }

        ch.tap("MetaLeft")

        assertArrayEquals(
            bytes(0x57, 0xAB, 0x00, 0x02, 0x08, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x14),
            frames[0],
        )
    }

    @Test
    fun absoluteScrollKeepsLastPosition() {
        val frames = mutableListOf<ByteArray>()
        val ch = Ch9329(absoluteMouse = true) { frames += it }

        ch.mouseMoveAbsolute(1f, 0f, 0)
        ch.mouseScroll(1)

        assertArrayEquals(
            bytes(0x57, 0xAB, 0x00, 0x04, 0x07, 0x02, 0x00, 0xFF, 0x0F, 0x00, 0x00, 0x01, 0x1E),
            frames.last(),
        )
    }

    @Test
    fun relativeScrollEncodesSignedWheel() {
        val frames = mutableListOf<ByteArray>()
        val ch = Ch9329(absoluteMouse = false) { frames += it }

        ch.mouseScroll(-1)
        ch.mouseScroll(0)

        assertArrayEquals(
            bytes(0x57, 0xAB, 0x00, 0x05, 0x05, 0x01, 0x00, 0x00, 0x00, 0xFF, 0x0C),
            frames.single(),
        )
    }

    @Test
    fun absoluteCoordsOutsideRangeAreClamped() {
        val frames = mutableListOf<ByteArray>()
        val ch = Ch9329(absoluteMouse = true) { frames += it }

        ch.mouseMoveAbsolute(-1f, 2f, 1)

        assertArrayEquals(
            bytes(0x57, 0xAB, 0x00, 0x04, 0x07, 0x02, 0x01, 0x00, 0x00, 0xFF, 0x0F, 0x00, 0x1E),
            frames.single(),
        )
    }

    @Test
    fun relativeMouseClampsSignedDeltas() {
        val frames = mutableListOf<ByteArray>()
        val ch = Ch9329(absoluteMouse = false) { frames += it }

        ch.mouseMoveRelative(200, -200, 2)

        assertArrayEquals(
            bytes(0x57, 0xAB, 0x00, 0x05, 0x05, 0x01, 0x02, 0x7F, 0x81, 0x00, 0x0F),
            frames.single(),
        )
    }

    @Test
    fun buttonTransitionsAreCommandsWhileMotionIsCoalescible() {
        val commands = mutableListOf<ByteArray>()
        val moves = mutableListOf<ByteArray>()
        val ch = Ch9329(
            absoluteMouse = true,
            writeMove = { moves += it },
            write = { commands += it },
        )

        ch.mouseMoveAbsolute(0.1f, 0.1f, buttons = 1)
        ch.mouseMoveAbsolute(0.2f, 0.2f, buttons = 1)
        ch.mouseMoveAbsolute(0.2f, 0.2f, buttons = 0)

        assertEquals(2, commands.size)
        assertEquals(1, moves.size)
    }

    @Test
    fun keyboardPacketsTrackKeysAndModifiers() {
        val frames = mutableListOf<ByteArray>()
        val ch = Ch9329(absoluteMouse = true) { frames += it }

        ch.keydown("ControlLeft")
        ch.keydown("KeyA")
        ch.keyup("KeyA")

        assertArrayEquals(
            bytes(0x57, 0xAB, 0x00, 0x02, 0x08, 0x01, 0x00, 0x04, 0, 0, 0, 0, 0, 0x11),
            frames[1],
        )
        assertArrayEquals(
            bytes(0x57, 0xAB, 0x00, 0x02, 0x08, 0x01, 0x00, 0, 0, 0, 0, 0, 0, 0x0D),
            frames[2],
        )
    }

    @Test
    fun releaseAllKeysPacket() {
        val frames = mutableListOf<ByteArray>()

        Ch9329(true) { frames += it }.releaseAllKeys()

        assertEquals(14, frames.single().size)
        assertEquals(0x0C.toByte(), frames.single().last())
        assertArrayEquals(
            bytes(0x57, 0xAB, 0x00, 0x02, 0x08, 0, 0, 0, 0, 0, 0, 0, 0, 0x0C),
            frames.single(),
        )
    }

    @Test
    fun ctrlAltDeleteSendsPressThenRelease() {
        val frames = mutableListOf<ByteArray>()

        Ch9329(true) { frames += it }.ctrlAltDelete()

        assertArrayEquals(
            bytes(0x57, 0xAB, 0x00, 0x02, 0x08, 0x05, 0x00, 0x4C, 0, 0, 0, 0, 0, 0x5D),
            frames.first(),
        )
        assertEquals(0x0C.toByte(), frames.last().last())
    }

    @Test
    fun tapComboHoldsModifiersFirstThenAddsKeysThenReleases() {
        val frames = mutableListOf<ByteArray>()

        assertTrue(Ch9329(true) { frames += it }.tapCombo(listOf("ControlLeft", "AltLeft", "F2")))

        assertEquals(
            listOf(
                listOf(0x05, 0, 0, 0, 0, 0, 0, 0),
                listOf(0x05, 0, 0x3B, 0, 0, 0, 0, 0),
                listOf(0, 0, 0, 0, 0, 0, 0, 0),
            ),
            frames.map { frame -> frame.slice(5..12).map { it.toInt() and 0xFF } },
        )
        for (frame in frames) {
            val sum = frame.dropLast(1).sumOf { it.toInt() and 0xFF } and 0xFF
            assertEquals(sum, frame.last().toInt() and 0xFF)
        }
    }

    @Test
    fun tapComboSkipsModifierOnlyPacketWhenThereIsNothingElse() {
        val plain = mutableListOf<ByteArray>()
        Ch9329(true) { plain += it }.tapCombo(listOf("Delete"))
        assertEquals(listOf(0x4C, 0), plain.map { it[7].toInt() })

        val winOnly = mutableListOf<ByteArray>()
        Ch9329(true) { winOnly += it }.tapCombo(listOf("MetaLeft"))
        assertEquals(listOf(0x08, 0x00), winOnly.map { it[5].toInt() })
    }

    @Test
    fun tapComboRefusesUnknownOrTooManyKeysWithoutSending() {
        val frames = mutableListOf<ByteArray>()
        val ch = Ch9329(true) { frames += it }

        assertFalse(ch.tapCombo(listOf("ControlLeft", "NoSuchKey")))
        assertFalse(ch.tapCombo(listOf("KeyA", "KeyB", "KeyC", "KeyD", "KeyE", "KeyF", "KeyG")))
        assertTrue(frames.isEmpty())
    }

    @Test
    fun tapComboClearsKeysHeldOnTheExternalKeyboard() {
        val frames = mutableListOf<ByteArray>()
        val ch = Ch9329(true) { frames += it }
        ch.keydown("ShiftLeft")
        ch.keydown("KeyA")

        ch.tapCombo(listOf("MetaLeft", "KeyL"))
        frames.clear()
        ch.keydown("KeyB")

        assertEquals(0x00, frames.single()[5].toInt())
        assertEquals(listOf(0x05, 0), frames.single().slice(7..8).map { it.toInt() })
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}
