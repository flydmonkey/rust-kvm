package com.rustkvm.client.hid

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PasteTyperTest {
    @Test
    fun typesAsciiAsOrderedPressReleasePairsAndSkipsUnsupportedCharacters() = runBlocking {
        val frames = mutableListOf<ByteArray>()
        val waits = mutableListOf<Long>()
        val typer = PasteTyper(
            ch9329 = Ch9329(absoluteMouse = true) { frames += it },
            wait = { waits += it },
        )

        val result = typer.typeText("aA!中\n")

        assertEquals(PasteResult(typed = 4, skipped = 1), result)
        assertEquals(8, frames.size)
        assertEquals(listOf(0x00, 0x00, 0x02, 0x00), frames.map { it[5].toInt() }.take(4))
        assertEquals(listOf(0x04, 0x00, 0x04, 0x00), frames.map { it[7].toInt() }.take(4))
        assertEquals(0x1E, frames[4][7].toInt())
        assertEquals(0x28, frames[6][7].toInt())
        assertEquals(List(8) { PasteTyper.PACKET_GAP_MS }, waits)
    }

    @Test
    fun modifierBitsCombineWithShiftAndAreReleasedAfterEachCharacter() = runBlocking {
        val frames = mutableListOf<ByteArray>()
        val typer = PasteTyper(
            ch9329 = Ch9329(absoluteMouse = true) { frames += it },
            wait = {},
        )

        typer.typeText("cC", modifierBits = 0x01)

        assertEquals(listOf(0x01, 0x00, 0x03, 0x00), frames.map { it[5].toInt() })
        assertEquals(listOf(0x06, 0x00, 0x06, 0x00), frames.map { it[7].toInt() })
    }
}
