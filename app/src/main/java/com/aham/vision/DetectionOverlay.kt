package com.aham.vision

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class DetectionOverlay(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private val boxPaint = Paint().apply { color = Color.YELLOW; style = Paint.Style.STROKE; strokeWidth = 7f; isAntiAlias = true }
    private val fillPaint = Paint().apply { color = Color.argb(235, 0, 0, 0); style = Paint.Style.FILL }
    private val textPaint = Paint().apply { color = Color.WHITE; textSize = 38f; isAntiAlias = true; isFakeBoldText = true }
    @Volatile private var detections: List<Detection> = emptyList()
    @Volatile private var sourceWidth = 1
    @Volatile private var sourceHeight = 1

    fun update(items: List<Detection>, imageWidth: Int, imageHeight: Int) {
        detections = items
        sourceWidth = imageWidth.coerceAtLeast(1)
        sourceHeight = imageHeight.coerceAtLeast(1)
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = maxOf(width.toFloat() / sourceWidth, height.toFloat() / sourceHeight)
        val offsetX = (width - sourceWidth * scale) / 2f
        val offsetY = (height - sourceHeight * scale) / 2f
        detections.forEach { d ->
            val l = offsetX + d.left * sourceWidth * scale
            val t = offsetY + d.top * sourceHeight * scale
            val r = offsetX + d.right * sourceWidth * scale
            val b = offsetY + d.bottom * sourceHeight * scale
            canvas.drawRect(l, t, r, b, boxPaint)
            val label = "${d.label} ${(d.score * 100).toInt()}%"
            val tw = textPaint.measureText(label)
            canvas.drawRect(l, (t - 44f).coerceAtLeast(0f), l + tw + 18f, t, fillPaint)
            canvas.drawText(label, l + 9f, (t - 10f).coerceAtLeast(34f), textPaint)
        }
    }
}
