package com.rustkvm.app.ui.input

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchInputModifierTest {
    @Test
    fun normalizesCoordinatesInsideLetterboxedVideo() {
        val point = normalizeLetterboxedTouch(
            x = 250f,
            y = 250f,
            viewportWidth = 1000f,
            viewportHeight = 500f,
            videoWidth = 500f,
            videoHeight = 500f,
        )

        assertEquals(0f, point?.x ?: -1f, 0.0001f)
        assertEquals(0.5f, point?.y ?: -1f, 0.0001f)
    }

    @Test
    fun rejectsTouchesInLetterboxBars() {
        val point = normalizeLetterboxedTouch(
            x = 249f,
            y = 250f,
            viewportWidth = 1000f,
            viewportHeight = 500f,
            videoWidth = 500f,
            videoHeight = 500f,
        )

        assertNull(point)
    }

    @Test
    fun normalizesVideoBottomRightToOne() {
        val point = normalizeLetterboxedTouch(
            x = 750f,
            y = 500f,
            viewportWidth = 1000f,
            viewportHeight = 500f,
            videoWidth = 500f,
            videoHeight = 500f,
        )

        assertEquals(1f, point?.x ?: -1f, 0.0001f)
        assertEquals(1f, point?.y ?: -1f, 0.0001f)
    }

    @Test
    fun absoluteDragStartsOnlyAfterSixPixelDeadZone() {
        val down = Offset(10f, 10f)

        assertFalse(hasExceededDragSlop(down, Offset(14f, 14f)))
        assertTrue(hasExceededDragSlop(down, Offset(17f, 10f)))
    }

    @Test
    fun relativeMotionCarriesSubPixelResidual() {
        val accumulator = RelativeMotionAccumulator()

        assertEquals(RelativeDelta(0, 0), accumulator.consume(0.4f, 0.4f))
        assertEquals(RelativeDelta(1, 1), accumulator.consume(0.4f, 0.4f))
        assertEquals(RelativeDelta(0, 0), accumulator.consume(0.4f, 0.4f))
        assertEquals(RelativeDelta(1, 1), accumulator.consume(0.4f, 0.4f))
    }
}
