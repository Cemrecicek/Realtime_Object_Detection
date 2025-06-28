package com.example.realtimeproject

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private val boxes = mutableListOf<RectFWithLabel>()
    private val paint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val textPaint = Paint().apply {
        color = Color.RED
        textSize = 48f
        style = Paint.Style.FILL
    }

    fun setBoxes(newBoxes: List<RectFWithLabel>) {
        boxes.clear()
        boxes.addAll(newBoxes)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (box in boxes) {
            canvas.drawRect(box.rect, paint)
            canvas.drawText(box.label, box.rect.left, box.rect.top - 10, textPaint)
        }
    }

    data class RectFWithLabel(val rect: android.graphics.RectF, val label: String)
}