package com.aham.vision

import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class YoloDetectorInstrumentedTest {
    @Test fun bundledModelDetectsLabeledObjectsWithValidBoxes() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val bitmap = context.assets.open("bus.jpg").use(BitmapFactory::decodeStream)
        val results = YoloDetector(context).use { it.detect(bitmap) }

        assertTrue("Expected at least one detection", results.isNotEmpty())
        assertTrue("Expected a person or bus label: $results", results.any { it.label == "person" || it.label == "bus" })
        assertTrue("Every result must have a visible box", results.all { it.left < it.right && it.top < it.bottom })
        assertTrue("Every result must have a caption label", results.all { it.label.isNotBlank() && it.score >= .25f })
    }
}
