package com.rustkvm.app.ui.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
