package com.rustkvm.client.hid

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}
