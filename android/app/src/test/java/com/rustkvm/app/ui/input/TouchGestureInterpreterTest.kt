package com.rustkvm.app.ui.input

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchGestureInterpreterTest {
    private val events = mutableListOf<String>()

    private val sink = object : PointerSink {
        override fun absolute(point: NormalizedTouch, buttons: Int) {
            events += "abs(${point.x},${point.y},$buttons)"
        }

        override fun relative(dx: Int, dy: Int, buttons: Int) {
            events += "rel($dx,$dy,$buttons)"
        }

        override fun scroll(notches: Int) {
            events += "scroll($notches)"
        }
    }

    /** 100x100 的视口直接映射到 0..1，便于读断言。 */
    private fun interpreter(absolute: Boolean) = TouchGestureInterpreter(
        absoluteMouse = absolute,
        normalize = { position, clamp ->
            val x = position.x / 100f
            val y = position.y / 100f
            if (!clamp && (x !in 0f..1f || y !in 0f..1f)) {
                null
            } else {
                NormalizedTouch(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
            }
        },
        sink = sink,
        slopPx = 5f,
        scrollPxPerNotch = 10f,
    )

    private fun down(id: Long, x: Float, y: Float) = TouchPoint(id, Offset(x, y), pressed = true)
    private fun up(id: Long, x: Float, y: Float) = TouchPoint(id, Offset(x, y), pressed = false)

    @Test
    fun absoluteTapMovesThenClicksLeft() {
        val gesture = interpreter(absolute = true)

        gesture.onEvent(listOf(down(1, 50f, 50f)))
        gesture.onEvent(listOf(up(1, 51f, 50f)))

        assertEquals(listOf("abs(0.5,0.5,0)", "abs(0.5,0.5,1)", "abs(0.5,0.5,0)"), events)
        assertTrue(gesture.finished)
    }

    @Test
    fun absoluteDragPressesAtStartPointOnlyAfterSlop() {
        val gesture = interpreter(absolute = true)

        gesture.onEvent(listOf(down(1, 10f, 10f)))
        gesture.onEvent(listOf(down(1, 12f, 10f)))
        gesture.onEvent(listOf(down(1, 30f, 10f)))
        gesture.onEvent(listOf(up(1, 40f, 10f)))

        assertEquals(
            listOf("abs(0.1,0.1,0)", "abs(0.1,0.1,1)", "abs(0.3,0.1,1)", "abs(0.4,0.1,0)"),
            events,
        )
    }

    @Test
    fun longPressClicksRightAndIgnoresTheRest() {
        val gesture = interpreter(absolute = true)

        gesture.onEvent(listOf(down(1, 20f, 20f)))
        gesture.onLongPress()
        gesture.onEvent(listOf(down(1, 60f, 60f)))
        gesture.onEvent(listOf(up(1, 60f, 60f)))

        assertEquals(listOf("abs(0.2,0.2,0)", "abs(0.2,0.2,2)", "abs(0.2,0.2,0)"), events)
    }

    @Test
    fun absoluteTapInLetterboxDoesNothing() {
        val gesture = interpreter(absolute = true)

        gesture.onEvent(listOf(down(1, 150f, 50f)))
        gesture.onEvent(listOf(up(1, 150f, 50f)))

        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun relativeDragMovesCursorWithoutPressing() {
        val gesture = interpreter(absolute = false)

        gesture.onEvent(listOf(down(1, 10f, 10f)))
        gesture.onEvent(listOf(down(1, 20f, 10f)))
        gesture.onEvent(listOf(down(1, 23f, 12f)))
        gesture.onEvent(listOf(up(1, 23f, 12f)))

        assertEquals(listOf("rel(10,0,0)", "rel(3,2,0)"), events)
    }

    @Test
    fun relativeTapClicksLeftInPlace() {
        val gesture = interpreter(absolute = false)

        gesture.onEvent(listOf(down(1, 10f, 10f)))
        gesture.onEvent(listOf(up(1, 10f, 10f)))

        assertEquals(listOf("rel(0,0,1)", "rel(0,0,0)"), events)
    }

    @Test
    fun twoFingerDragScrollsWithNaturalDirection() {
        val gesture = interpreter(absolute = false)

        gesture.onEvent(listOf(down(1, 40f, 50f)))
        gesture.onEvent(listOf(down(1, 40f, 50f), down(2, 60f, 50f)))
        // 手指上推 25px：内容上移，即向下滚 2 格，余下 5px 留到下一次
        gesture.onEvent(listOf(down(1, 40f, 25f), down(2, 60f, 25f)))
        gesture.onEvent(listOf(down(1, 40f, 20f), down(2, 60f, 20f)))
        gesture.onEvent(listOf(up(1, 40f, 20f), down(2, 60f, 20f)))

        assertEquals(listOf("scroll(-2)", "scroll(-1)"), events)
    }

    @Test
    fun twoFingerTapClicksRight() {
        val gesture = interpreter(absolute = true)

        gesture.onEvent(listOf(down(1, 40f, 50f)))
        gesture.onEvent(listOf(down(1, 40f, 50f), down(2, 60f, 50f)))
        gesture.onEvent(listOf(up(1, 40f, 50f), up(2, 60f, 50f)))

        assertEquals(listOf("abs(0.4,0.5,0)", "abs(0.4,0.5,2)", "abs(0.4,0.5,0)"), events)
        assertTrue(gesture.finished)
    }
}
