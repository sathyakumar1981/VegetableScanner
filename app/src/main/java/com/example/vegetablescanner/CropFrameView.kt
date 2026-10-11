package com.example.vegetablescanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Draws a dimmed overlay with a clear square "window" in the centre of the screen.
 * Only the picture inside the window is used for teaching and recognising.
 */
class CropFrameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val dimPaint = Paint().apply { color = Color.argb(120, 0, 0, 0) }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 3f
    }
    private val rect = RectF()

    /** Side of the window as a fraction (0.3 to 0.95) of the shorter screen side. */
    var fraction: Float = 0.6f
        set(value) {
            field = value.coerceIn(0.3f, 0.95f)
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val side = fraction * minOf(w, h)
        rect.set(w / 2f - side / 2f, h / 2f - side / 2f, w / 2f + side / 2f, h / 2f + side / 2f)

        // Dim everything outside the window
        canvas.drawRect(0f, 0f, w, rect.top, dimPaint)
        canvas.drawRect(0f, rect.bottom, w, h, dimPaint)
        canvas.drawRect(0f, rect.top, rect.left, rect.bottom, dimPaint)
        canvas.drawRect(rect.right, rect.top, w, rect.bottom, dimPaint)

        canvas.drawRoundRect(rect, 16f, 16f, borderPaint)
    }
}
