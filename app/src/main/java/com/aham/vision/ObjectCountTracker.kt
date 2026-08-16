package com.aham.vision

object ObjectCounter {
    fun matching(detections: List<Detection>, target: String): List<Detection> =
        detections.filter { it.label.equals(target, ignoreCase = true) }
}

/** Stabilizes live counts by returning the median of the latest detection frames. */
class ObjectCountTracker(private val windowSize: Int = 5) {
    private val recent = ArrayDeque<Int>()

    fun update(count: Int): Int {
        recent.addLast(count.coerceAtLeast(0))
        while (recent.size > windowSize) recent.removeFirst()
        return recent.sorted()[recent.size / 2]
    }
}
