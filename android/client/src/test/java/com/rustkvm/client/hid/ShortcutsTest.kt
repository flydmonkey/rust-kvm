package com.rustkvm.client.hid

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutsTest {
    private fun combo(text: String) = (Shortcuts.parseCombo(text) as Shortcuts.Parsed.Ok).value
    private fun sequence(text: String) = (Shortcuts.parseSequence(text) as Shortcuts.Parsed.Ok).value
    private fun error(result: Shortcuts.Parsed<*>) = (result as Shortcuts.Parsed.Invalid).message

    @Test
    fun modifiersComeFirstAndDisplayIsNormalized() {
        assertEquals(Shortcuts.Combo(listOf("ControlLeft", "AltLeft", "F2"), "Ctrl+Alt+F2"), combo("Ctrl+Alt+F2"))
        assertEquals(
            Shortcuts.Combo(listOf("ControlLeft", "ShiftLeft", "Escape"), "Ctrl+Shift+Esc"),
            combo(" ctrl + shift + esc "),
        )
        assertEquals(listOf("MetaLeft", "KeyL"), combo("L+Win").codes)
        assertEquals(Shortcuts.Combo(listOf("MetaLeft"), "Win"), combo("Win"))
        assertEquals("Cmd+Option+Esc", combo("cmd+option+esc").text)
        assertEquals(listOf("ControlLeft", "KeyA"), combo("Ctrl+Control+A+a").codes)
        assertEquals(listOf("ControlLeft", "Minus"), combo("Ctrl+-").codes)
        assertEquals(listOf("Digit7"), combo("7").codes)
    }

    @Test
    fun invalidCombosExplainWhy() {
        assertTrue(error(Shortcuts.parseCombo("Ctrl+F13")).contains("F13"))
        assertTrue(error(Shortcuts.parseCombo("Hyper+A")).contains("Hyper"))
        assertEquals("组合键是空的", error(Shortcuts.parseCombo("  ")))
        assertTrue(error(Shortcuts.parseCombo("Ctrl++A")).contains("+"))
        assertTrue(error(Shortcuts.parseCombo("Ctrl+")).contains("+"))
        assertTrue(Shortcuts.parseCombo("A+B+C+D+E+F") is Shortcuts.Parsed.Ok)
        assertTrue(error(Shortcuts.parseCombo("A+B+C+D+E+F+G")).contains("6"))
    }

    @Test
    fun sequencesSplitOnSpacesAndCommas() {
        assertEquals(
            Shortcuts.Sequence(listOf(listOf("MetaLeft", "KeyR"), listOf("Enter")), "Win+R Enter"),
            sequence("Win+R, enter"),
        )
        assertEquals(2, sequence("Win+R，Enter").steps.size)
        assertTrue(error(Shortcuts.parseSequence("Win+R Foo")).contains("Foo"))
        val many = List(Shortcuts.MAX_STEPS + 1) { "A" }.joinToString(" ")
        assertTrue(Shortcuts.parseSequence(many) is Shortcuts.Parsed.Invalid)
    }

    @Test
    fun everyPresetParsesIsCanonicalAndEncodes() {
        assertEquals(
            listOf("Windows", "macOS", "Linux", "开机 / BIOS", "其他"),
            Shortcuts.PRESETS.map { it.title },
        )
        for (group in Shortcuts.PRESETS) {
            for (item in group.items) {
                val parsed = sequence(item.combo)
                assertEquals(item.combo, parsed.text)
                for (codes in parsed.steps) {
                    val frames = mutableListOf<ByteArray>()
                    assertTrue("${item.combo} 协议层发不出去", Ch9329(true) { frames += it }.tapCombo(codes))
                }
            }
        }
        assertEquals(listOf("开机 / BIOS"), Shortcuts.PRESETS.filter { it.repeatable }.map { it.title })
    }

    @Test
    fun everyParserTokenIsKnownToTheProtocolLayer() {
        val tokens = listOf(
            "Ctrl", "Shift", "Alt", "Option", "Win", "Super", "Cmd",
            "Esc", "Tab", "Enter", "Space", "Backspace", "Del", "Ins", "Home", "End", "PgUp", "PgDn",
            "Up", "Down", "Left", "Right", "PrtSc", "SysRq", "Pause", "Break", "CapsLock", "NumLock",
            "ScrollLock", "Menu", "-", "=", "[", "]", "\\", ";", "'", ",", ".", "/", "`",
        ) + ('A'..'Z').map(Char::toString) + (0..9).map(Int::toString) + (1..12).map { "F$it" }
        for (token in tokens) {
            val code = combo(token).codes.single()
            assertTrue(
                "$token -> $code",
                code in Ch9329Maps.CONTROL_KEY_BITS || code in Ch9329Maps.KEYBOARD_MAP,
            )
        }
    }

    @Test
    fun customShortcutsNormalizeAndRoundTrip() {
        assertEquals(
            Shortcuts.Parsed.Ok(Shortcuts.Shortcut("Ctrl+Alt+F2", "Ctrl+Alt+F2")),
            Shortcuts.makeCustom("", "ctrl+alt+f2"),
        )
        val long = (Shortcuts.makeCustom("  " + "很".repeat(30), "A") as Shortcuts.Parsed.Ok).value
        assertEquals(Shortcuts.MAX_LABEL_LENGTH, long.label.length)
        val tabbed = (Shortcuts.makeCustom("a\tb\nc", "A") as Shortcuts.Parsed.Ok).value
        assertEquals("a b c", tabbed.label)
        assertTrue(Shortcuts.makeCustom("x", "Ctrl+Nope") is Shortcuts.Parsed.Invalid)

        val list = listOf(Shortcuts.Shortcut("TTY2", "ctrl+alt+f2"), Shortcuts.Shortcut("坏的", "Ctrl+Nope"))
        val decoded = Shortcuts.decodeCustom(Shortcuts.encodeCustom(list))
        assertEquals(listOf(Shortcuts.Shortcut("TTY2", "Ctrl+Alt+F2")), decoded)
        assertEquals(emptyList<Shortcuts.Shortcut>(), Shortcuts.decodeCustom(null))
        assertEquals(emptyList<Shortcuts.Shortcut>(), Shortcuts.decodeCustom("没有制表符\n\n"))

        val many = List(Shortcuts.MAX_CUSTOM + 5) { Shortcuts.Shortcut("k$it", "A") }
        assertEquals(Shortcuts.MAX_CUSTOM, Shortcuts.normalizeCustomList(many).size)
    }

    @Test
    fun sequenceStopsAtFirstFailedStep() = runBlocking {
        val sent = mutableListOf<List<String>>()
        val ok = sendShortcutSteps(listOf(listOf("A"), listOf("B"), listOf("C")), gapMs = 1) {
            sent += it
            it != listOf("B")
        }
        assertFalse(ok)
        assertEquals(listOf(listOf("A"), listOf("B")), sent)
    }

    @Test
    fun repeatEndsAtTimeLimit() = runBlocking {
        var clock = 0L
        var counted = 0
        val end = repeatShortcutSteps(
            steps = listOf(listOf("Delete")),
            intervalMs = 1,
            maxMs = 1_000,
            now = { clock },
            onCount = { counted = it },
        ) {
            clock += 200
            true
        }
        assertEquals(RepeatEnd.TimedOut, end)
        assertEquals(5, counted)
    }

    @Test
    fun repeatEndsWhenSendingFails() = runBlocking {
        var calls = 0
        val end = repeatShortcutSteps(steps = listOf(listOf("F2")), intervalMs = 1, now = { 0 }) {
            calls += 1
            calls < 3
        }
        assertEquals(RepeatEnd.Failed, end)
        assertEquals(3, calls)
    }
}
