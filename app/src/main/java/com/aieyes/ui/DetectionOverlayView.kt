package com.aieyes.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.aieyes.detect.Detection
import kotlin.math.max

/**
 * Simple box overlay, mainly useful for a sighted helper that aims the phone
 * while the blind user listens to the spoken guidance.
 */
class DetectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var detections: List<Detection> = emptyList()
    private var highlightClassIndex: Int? = null

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 3f
        color = Color.parseColor("#35C77A")
    }

    private val highlightBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 5f
        color = Color.parseColor("#FFB300")
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = resources.displayMetrics.density * 18f
        setShadowLayer(4f, 1f, 1f, Color.BLACK)
    }

    fun setDetections(list: List<Detection>, highlightClassIndex: Int? = null) {
        detections = list
        this.highlightClassIndex = highlightClassIndex
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return
        for (d in detections) {
            val isTarget = highlightClassIndex != null && d.classIndex == highlightClassIndex
            val paint = if (isTarget) highlightBoxPaint else boxPaint
            val l = d.left * w
            val t = d.top * h
            val r = d.right * w
            val b = d.bottom * h
            canvas.drawRect(l, t, r, b, paint)
            val label = "${d.label} ${(d.score * 100).toInt()}%"
            canvas.drawText(label, l, max(textPaint.textSize, t - 8f), textPaint)
        }
    }
}
