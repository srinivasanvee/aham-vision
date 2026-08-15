package com.aham.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import java.io.File
import kotlin.math.max
import kotlin.math.min

data class Detection(val left: Float, val top: Float, val right: Float, val bottom: Float, val score: Float, val label: String)

/** Runs the bundled Ultralytics LiteRT export entirely on-device. */
class YoloDetector(context: Context) : AutoCloseable {
    private val labels = context.assets.open("coco_labels.txt").bufferedReader().readLines()
    private val modelFile = File(context.codeCacheDir, MODEL_NAME).also { target ->
        if (!target.exists() || target.length() == 0L) {
            context.assets.open(MODEL_NAME).use { input -> target.outputStream().use(input::copyTo) }
        }
    }
    private val model = CompiledModel.create(
        modelFile.absolutePath,
        CompiledModel.Options(Accelerator.CPU).apply {
            cpuOptions = CompiledModel.CpuOptions(numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
        },
    )
    private val inputBuffers: List<TensorBuffer> = model.createInputBuffers()
    private val outputBuffers: List<TensorBuffer> = model.createOutputBuffers()
    private val nativeInputShape = model.getInputTensorType(inputName = "args_0").layout!!.dimensions.toIntArray()
    private val nchw = nativeInputShape.size == 4 && nativeInputShape[1] == 3
    private val inputHeight = if (nchw) nativeInputShape[2] else nativeInputShape[1]
    private val inputWidth = if (nchw) nativeInputShape[3] else nativeInputShape[2]
    private val outputShape = model.getOutputTensorType(outputName = "output_0").layout!!.dimensions.toIntArray()

    fun detect(source: Bitmap, threshold: Float = 0.25f): List<Detection> {
        val scale = min(inputWidth.toFloat() / source.width, inputHeight.toFloat() / source.height)
        val scaledWidth = (source.width * scale).toInt().coerceAtLeast(1)
        val scaledHeight = (source.height * scale).toInt().coerceAtLeast(1)
        val padX = (inputWidth - scaledWidth) / 2f
        val padY = (inputHeight - scaledHeight) / 2f
        val letterboxed = Bitmap.createBitmap(inputWidth, inputHeight, Bitmap.Config.ARGB_8888)
        Canvas(letterboxed).apply {
            drawColor(Color.rgb(114, 114, 114))
            drawBitmap(source, null, Rect(padX.toInt(), padY.toInt(), padX.toInt() + scaledWidth, padY.toInt() + scaledHeight), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        val pixels = IntArray(inputWidth * inputHeight)
        letterboxed.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        val input = FloatArray(pixels.size * 3)
        if (nchw) {
            val plane = pixels.size
            pixels.forEachIndexed { i, pixel ->
                input[i] = ((pixel shr 16) and 255) / 255f
                input[plane + i] = ((pixel shr 8) and 255) / 255f
                input[plane * 2 + i] = (pixel and 255) / 255f
            }
        } else {
            pixels.forEachIndexed { i, pixel ->
                input[i * 3] = ((pixel shr 16) and 255) / 255f
                input[i * 3 + 1] = ((pixel shr 8) and 255) / 255f
                input[i * 3 + 2] = (pixel and 255) / 255f
            }
        }
        inputBuffers[0].writeFloat(input)
        model.run(inputBuffers, outputBuffers)
        return decode(outputBuffers[0].readFloat(), outputShape, labels, threshold, source.width, source.height,
            inputWidth, inputHeight, scale, padX, padY)
    }

    override fun close() {
        inputBuffers.forEach { runCatching { it.close() } }
        outputBuffers.forEach { runCatching { it.close() } }
        runCatching { model.close() }
    }

    companion object {
        private const val MODEL_NAME = "yolo26n_w8a32.tflite"

        internal fun decode(output: FloatArray, shape: IntArray, labels: List<String>, threshold: Float,
            sourceWidth: Int, sourceHeight: Int, inputWidth: Int, inputHeight: Int,
            scale: Float, padX: Float, padY: Float): List<Detection> {
            require(shape.size == 3 && shape[0] == 1) { "Unsupported YOLO output: ${shape.contentToString()}" }
            // Newer Ultralytics exports can include NMS and return [1, detections, 6]:
            // x1, y1, x2, y2, confidence, class index.
            if (shape[2] in 6..7) {
                val rowLength = shape[2]
                val found = ArrayList<Detection>()
                for (i in 0 until shape[1]) {
                    val base = i * rowLength
                    val score = output[base + 4]
                    val classIndex = output[base + 5].toInt()
                    if (score < threshold || classIndex !in labels.indices) continue
                    var left = output[base]
                    var top = output[base + 1]
                    var right = output[base + 2]
                    var bottom = output[base + 3]
                    if (max(max(kotlin.math.abs(left), kotlin.math.abs(top)), max(kotlin.math.abs(right), kotlin.math.abs(bottom))) <= 2f) {
                        left *= inputWidth; right *= inputWidth
                        top *= inputHeight; bottom *= inputHeight
                    }
                    left = ((left - padX) / scale / sourceWidth).coerceIn(0f, 1f)
                    top = ((top - padY) / scale / sourceHeight).coerceIn(0f, 1f)
                    right = ((right - padX) / scale / sourceWidth).coerceIn(0f, 1f)
                    bottom = ((bottom - padY) / scale / sourceHeight).coerceIn(0f, 1f)
                    if (right > left && bottom > top) found += Detection(left, top, right, bottom, score, labels[classIndex])
                }
                return found.sortedByDescending(Detection::score).take(30)
            }
            val channelsFirst = shape[1] <= labels.size + 5
            val channels = if (channelsFirst) shape[1] else shape[2]
            val candidates = if (channelsFirst) shape[2] else shape[1]
            require(channels >= labels.size + 4) { "YOLO output has only $channels features" }
            fun value(channel: Int, candidate: Int) = if (channelsFirst) output[channel * candidates + candidate] else output[candidate * channels + channel]
            val found = ArrayList<Detection>()
            for (i in 0 until candidates) {
                var bestClass = 0; var score = Float.NEGATIVE_INFINITY
                for (classIndex in labels.indices) {
                    val classScore = value(classIndex + 4, i)
                    if (classScore > score) { score = classScore; bestClass = classIndex }
                }
                if (score < threshold) continue
                val cx = value(0, i); val cy = value(1, i); val width = value(2, i); val height = value(3, i)
                val left = ((cx - width / 2f - padX) / scale / sourceWidth).coerceIn(0f, 1f)
                val top = ((cy - height / 2f - padY) / scale / sourceHeight).coerceIn(0f, 1f)
                val right = ((cx + width / 2f - padX) / scale / sourceWidth).coerceIn(0f, 1f)
                val bottom = ((cy + height / 2f - padY) / scale / sourceHeight).coerceIn(0f, 1f)
                if (right > left && bottom > top) found += Detection(left, top, right, bottom, score, labels[bestClass])
            }
            return nms(found.sortedByDescending(Detection::score)).take(30)
        }

        private fun nms(items: List<Detection>, threshold: Float = 0.7f): List<Detection> {
            val result = mutableListOf<Detection>()
            items.forEach { item -> if (result.none { it.label == item.label && iou(it, item) > threshold }) result += item }
            return result
        }

        private fun iou(a: Detection, b: Detection): Float {
            val intersection = max(0f, min(a.right, b.right) - max(a.left, b.left)) * max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
            val union = (a.right - a.left) * (a.bottom - a.top) + (b.right - b.left) * (b.bottom - b.top) - intersection
            return intersection / (union + 1e-6f)
        }
    }
}
