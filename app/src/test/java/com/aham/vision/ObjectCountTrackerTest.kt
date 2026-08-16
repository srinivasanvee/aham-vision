package com.aham.vision

import org.junit.Assert.assertEquals
import org.junit.Test

class ObjectCountTrackerTest {
    @Test fun medianCountIgnoresSingleFrameDropout() {
        val tracker = ObjectCountTracker(5)
        listOf(4, 4, 0, 4, 4).forEach(tracker::update)
        assertEquals(4, tracker.update(4))
    }

    @Test fun countCannotBeNegative() {
        assertEquals(0, ObjectCountTracker().update(-2))
    }

    @Test fun countsOnlyObjectsMatchingTheSelectedClass() {
        val detections = listOf(
            Detection(0f, 0f, .2f, .2f, .9f, "sports ball"),
            Detection(.2f, .2f, .4f, .4f, .8f, "person"),
            Detection(.4f, .4f, .6f, .6f, .7f, "Sports Ball"),
        )

        assertEquals(2, ObjectCounter.matching(detections, "sports ball").size)
    }
}
