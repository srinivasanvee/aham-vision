package com.aham.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YoloDetectorTest {
    @Test fun decodesChannelsFirstOutputAndRemovesLetterboxPadding() {
        val labels = listOf("person", "bird")
        val values = FloatArray(12)
        fun set(channel: Int, candidate: Int, value: Float) { values[channel * 2 + candidate] = value }
        set(0, 0, 320f); set(1, 0, 320f); set(2, 0, 320f); set(3, 0, 320f); set(4, 0, .9f)
        set(0, 1, 50f); set(1, 1, 50f); set(2, 1, 10f); set(3, 1, 10f); set(5, 1, .1f)

        val result = YoloDetector.decode(values, intArrayOf(1, 6, 2), labels, .25f, 1280, 720, .5f, 0f, 140f)

        assertEquals(1, result.size)
        assertEquals("person", result.single().label)
        assertEquals(.25f, result.single().left, .001f)
        assertEquals(.75f, result.single().right, .001f)
        assertTrue(result.single().top < result.single().bottom)
    }
}
