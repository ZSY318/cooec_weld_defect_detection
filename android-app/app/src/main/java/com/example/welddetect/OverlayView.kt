package com.example.welddetect

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** 在相机预览之上绘制检测框、标签,以及对准用的中心引导框 */
class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var detections: List<Detection> = emptyList()
    private var srcWidth = 1
    private var srcHeight = 1

    /** 归一化(0~1)的中心引导框,非空时绘制;用于提示工人把焊缝对准框内 */
    private var guideFrac: RectF? = null

    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.rgb(64, 220, 120)
        pathEffect = DashPathEffect(floatArrayOf(28f, 18f), 0f)
    }
    private val guideHintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 40f
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

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

    /** 设置归一化中心引导框(传 null 关闭);srcW/srcH 为预览图像尺寸 */
    fun setGuide(frac: RectF?, srcW: Int, srcH: Int) {
        guideFrac = frac
        srcWidth = srcW
        srcHeight = srcH
        detections = emptyList()
        invalidate()
    }

    /** 用已有的 src 尺寸重新显示/关闭引导框(冻结结果后恢复实时用) */
    fun setGuide(frac: RectF?) {
        guideFrac = frac
        detections = emptyList()
        invalidate()
    }

    fun clearGuide() {
        guideFrac = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // PreviewView 默认 FILL_CENTER:取较大缩放比并居中裁剪,这里保持一致
        val scale = maxOf(width.toFloat() / srcWidth, height.toFloat() / srcHeight)
        val dx = (width - srcWidth * scale) / 2f
        val dy = (height - srcHeight * scale) / 2f

        guideFrac?.let { g ->
            val l = g.left * srcWidth * scale + dx
            val t = g.top * srcHeight * scale + dy
            val r = g.right * srcWidth * scale + dx
            val b = g.bottom * srcHeight * scale + dy
            canvas.drawRoundRect(l, t, r, b, 24f, 24f, guidePaint)
            canvas.drawText("将焊缝置于框内后点击检测", (l + r) / 2f, t - 24f, guideHintPaint)
        }

        for (d in detections) {
            val color = palette[d.label.hashCode().mod(palette.size)]
            boxPaint.color = color
            textBgPaint.color = color
            textBgPaint.alpha = 140 // 半透明,避免标签底完全遮挡画面

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
