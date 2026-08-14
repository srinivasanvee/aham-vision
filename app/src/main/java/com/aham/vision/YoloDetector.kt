package com.aham.vision

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

data class Detection(val left: Float, val top: Float, val right: Float, val bottom: Float, val score: Float, val label: String)

class YoloDetector(context: Context) : AutoCloseable {
    private val labels = context.assets.open("coco_labels.txt").bufferedReader().readLines()
    private val model = context.assets.openFd("yolo26n_w8a32.tflite").use { fd ->
        fd.createInputStream().channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
    }
    private val interpreter = Interpreter(model, Interpreter.Options().apply { setNumThreads(4); setUseXNNPACK(true) })
    private val inputShape = interpreter.getInputTensor(0).shape()
    private val size = if (inputShape[1] == 3) inputShape[2] else inputShape[1]
    private val nchw = inputShape[1] == 3

    fun detect(source: Bitmap, threshold: Float = 0.35f): List<Detection> {
        val bitmap = Bitmap.createScaledBitmap(source, size, size, true)
        val input = ByteBuffer.allocateDirect(4 * size * size * 3).order(ByteOrder.nativeOrder())
        val pixels = IntArray(size * size); bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
        if (nchw) for (c in 0..2) pixels.forEach { p -> input.putFloat(((p shr (16 - c * 8)) and 255) / 255f) }
        else pixels.forEach { p -> input.putFloat(((p shr 16) and 255) / 255f); input.putFloat(((p shr 8) and 255) / 255f); input.putFloat((p and 255) / 255f) }
        val shape = interpreter.getOutputTensor(0).shape()
        val output = Array(shape[0]) { Array(shape[1]) { FloatArray(shape[2]) } }
        interpreter.run(input, output)
        val channelsFirst = shape[1] < shape[2]
        val candidates = if (channelsFirst) shape[2] else shape[1]
        val channels = if (channelsFirst) shape[1] else shape[2]
        fun value(c: Int, i: Int) = if (channelsFirst) output[0][c][i] else output[0][i][c]
        val found = ArrayList<Detection>()
        for (i in 0 until candidates) {
            var bestClass = 0; var score = 0f
            for (c in 4 until channels) if (value(c, i) > score) { score = value(c, i); bestClass = c - 4 }
            if (score < threshold || bestClass !in labels.indices) continue
            val cx = value(0, i) / size; val cy = value(1, i) / size
            val w = value(2, i) / size; val h = value(3, i) / size
            found += Detection((cx-w/2).coerceIn(0f,1f), (cy-h/2).coerceIn(0f,1f), (cx+w/2).coerceIn(0f,1f), (cy+h/2).coerceIn(0f,1f), score, labels[bestClass])
        }
        return nms(found.sortedByDescending { it.score }).take(30)
    }

    private fun nms(items: List<Detection>, threshold: Float = 0.45f): List<Detection> {
        val result = mutableListOf<Detection>()
        items.forEach { item -> if (result.none { it.label == item.label && iou(it, item) > threshold }) result += item }
        return result
    }
    private fun iou(a: Detection, b: Detection): Float {
        val area = max(0f, min(a.right,b.right)-max(a.left,b.left)) * max(0f,min(a.bottom,b.bottom)-max(a.top,b.top))
        return area / ((a.right-a.left)*(a.bottom-a.top)+(b.right-b.left)*(b.bottom-b.top)-area+1e-6f)
    }
    override fun close() = interpreter.close()
}
