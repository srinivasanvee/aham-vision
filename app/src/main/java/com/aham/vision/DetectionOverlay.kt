package com.aham.vision

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class DetectionOverlay(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private val boxPaint = Paint().apply { color = Color.rgb(115, 231, 189); style = Paint.Style.STROKE; strokeWidth = 5f }
    private val fillPaint = Paint().apply { color = Color.argb(210, 8, 13, 15); style = Paint.Style.FILL }
    private val textPaint = Paint().apply { color = Color.WHITE; textSize = 34f; isAntiAlias = true }
    @Volatile private var detections: List<Detection> = emptyList()

    fun update(items: List<Detection>) { detections = items; postInvalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        detections.forEach { d ->
            val l = d.left * width; val t = d.top * height; val r = d.right * width; val b = d.bottom * height
            canvas.drawRect(l, t, r, b, boxPaint)
            val label = "${d.label} ${(d.score * 100).toInt()}%"
            val tw = textPaint.measureText(label)
            canvas.drawRect(l, (t - 44f).coerceAtLeast(0f), l + tw + 18f, t, fillPaint)
            canvas.drawText(label, l + 9f, (t - 10f).coerceAtLeast(34f), textPaint)
        }
    }
}
