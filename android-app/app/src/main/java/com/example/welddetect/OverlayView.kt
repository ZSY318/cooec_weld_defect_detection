package com.example.welddetect

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** 在相机预览之上绘制检测框和标签 */
class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var detections: List<Detection> = emptyList()
    private var srcWidth = 1
    private var srcHeight = 1

    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 42f
        isFakeBoldText = true
    }
    private val textBgPaint = Paint().apply { style = Paint.Style.FILL }

    private val palette = intArrayOf(
        Color.rgb(255, 64, 64), Color.rgb(64, 160, 255), Color.rgb(64, 220, 120),
        Color.rgb(255, 180, 0), Color.rgb(200, 90, 255), Color.rgb(0, 220, 220),
    )

    /** @param srcW/srcH 检测所用图像的尺寸,用于把框坐标缩放到本视图 */
    fun update(results: List<Detection>, srcW: Int, srcH: Int) {
        detections = results
        srcWidth = srcW
        srcHeight = srcH
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // PreviewView 默认 FILL_CENTER:取较大缩放比并居中裁剪,这里保持一致
        val scale = maxOf(width.toFloat() / srcWidth, height.toFloat() / srcHeight)
        val dx = (width - srcWidth * scale) / 2f
        val dy = (height - srcHeight * scale) / 2f

        for (d in detections) {
            val color = palette[d.label.hashCode().mod(palette.size)]
            boxPaint.color = color
            textBgPaint.color = color

            val l = d.box.left * scale + dx
            val t = d.box.top * scale + dy
            val r = d.box.right * scale + dx
            val b = d.box.bottom * scale + dy
            canvas.drawRect(l, t, r, b, boxPaint)

            val text = "${LabelDisplay.name(d.label)} ${"%.2f".format(d.score)}"
            val tw = textPaint.measureText(text)
            canvas.drawRect(l, t - 52f, l + tw + 16f, t, textBgPaint)
            canvas.drawText(text, l + 8f, t - 12f, textPaint)
        }
    }
}
