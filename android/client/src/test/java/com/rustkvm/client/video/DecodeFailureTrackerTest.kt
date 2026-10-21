package com.rustkvm.client.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecodeFailureTrackerTest {
    @Test
    fun notifiesOnceWhenFailuresReachThreshold() {
        val tracker = DecodeFailureTracker(threshold = 3)

        assertEquals(listOf(false, false, true, false), List(4) { tracker.onFailure() })
        assertTrue(tracker.troubled)
    }

    @Test
    fun renderedFrameBetweenFailuresStartsOver() {
        val tracker = DecodeFailureTracker(threshold = 3)
        tracker.onFailure()
        tracker.onFailure()

        assertFalse(tracker.onFrameRendered())
        assertFalse(tracker.onFailure())
        assertFalse(tracker.onFailure())
        assertTrue(tracker.onFailure())
    }

    @Test
    fun recoveryIsReportedOnlyWhenPreviouslyTroubled() {
        val tracker = DecodeFailureTracker(threshold = 1)
        tracker.onFailure()

        assertTrue(tracker.onFrameRendered())
        assertFalse(tracker.onFrameRendered())
    }
}
