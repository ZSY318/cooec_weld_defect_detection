package com.example.welddetect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

data class Detection(val box: RectF, val label: String, val score: Float)

/**
 * YOLO TFLite 推理器。
 *
 * 自动适配两种输出格式:
 *  - YOLO26 端到端导出: [1, N, 6] -> (x1, y1, x2, y2, score, cls),无需 NMS
 *  - YOLOv8 经典导出:  [1, 4+nc, anchors] -> 需解码 + NMS
 *
 * 模型仓库:assets/models/ 下成对的 <模型名>.tflite + <模型名>.txt(类别标签)。
 * 由 [ModelRepository] 统一管理与切换,构造时传入要加载的模型名。
 */
class Detector(
    context: Context,
    modelName: String,
    private val confThreshold: Float = 0.25f,
    private val iouThreshold: Float = 0.45f,
) {
    private val interpreter: Interpreter
    private val labels: List<String>
    private val inputSize: Int

    init {
        interpreter = Interpreter(
            loadModel(context, "models/$modelName.tflite"),
            Interpreter.Options().apply { numThreads = 4 }
        )
        labels = context.assets.open("models/$modelName.txt").bufferedReader().readLines()
            .map { it.trim() }.filter { it.isNotEmpty() }
        // 输入形状 [1, H, W, 3]
        inputSize = interpreter.getInputTensor(0).shape()[1]
    }

    private fun loadModel(context: Context, name: String): ByteBuffer {
        context.assets.openFd(name).use { fd ->
            FileInputStream(fd.fileDescriptor).channel.use { ch ->
                return ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }
    }

    /** 对任意尺寸 Bitmap 推理,返回原图坐标系下的检测结果 */
    fun detect(bitmap: Bitmap): List<Detection> {
        // letterbox:等比缩放 + 灰边填充,与训练时一致
        val scale = minOf(inputSize.toFloat() / bitmap.width, inputSize.toFloat() / bitmap.height)
        val newW = (bitmap.width * scale).toInt()
        val newH = (bitmap.height * scale).toInt()
        val padX = (inputSize - newW) / 2f
        val padY = (inputSize - newH) / 2f

        val letterboxed = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
        Canvas(letterboxed).apply {
            drawColor(Color.rgb(114, 114, 114))
            drawBitmap(
                Bitmap.createScaledBitmap(bitmap, newW, newH, true),
                padX, padY, Paint(Paint.FILTER_BITMAP_FLAG)
            )
        }

        val input = bitmapToBuffer(letterboxed)
        val outShape = interpreter.getOutputTensor(0).shape()
        val raw = parse(input, outShape)

        // 把 letterbox 坐标映射回原图
        return raw.mapNotNull { (box, score, cls) ->
            val r = RectF(
                ((box.left - padX) / scale).coerceIn(0f, bitmap.width.toFloat()),
                ((box.top - padY) / scale).coerceIn(0f, bitmap.height.toFloat()),
                ((box.right - padX) / scale).coerceIn(0f, bitmap.width.toFloat()),
                ((box.bottom - padY) / scale).coerceIn(0f, bitmap.height.toFloat()),
            )
            if (r.width() < 1 || r.height() < 1) null
            else Detection(r, labels.getOrElse(cls) { "类别$cls" }, score)
        }
    }

    private fun bitmapToBuffer(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3)
            .order(ByteOrder.nativeOrder())
        val pixels = IntArray(inputSize * inputSize)
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        for (p in pixels) {
            buffer.putFloat(((p shr 16) and 0xFF) / 255f)
            buffer.putFloat(((p shr 8) and 0xFF) / 255f)
            buffer.putFloat((p and 0xFF) / 255f)
        }
        buffer.rewind()
        return buffer
    }

    /** 返回 letterbox 输入坐标系下的 (box, score, classIndex) */
    private fun parse(input: ByteBuffer, outShape: IntArray): List<Triple<RectF, Float, Int>> {
        return if (outShape.size == 3 && outShape[2] in 5..7) {
            parseEndToEnd(input, outShape)   // [1, N, 6]
        } else {
            parseRawWithNms(input, outShape) // [1, 4+nc, anchors]
        }
    }

    private fun parseEndToEnd(input: ByteBuffer, outShape: IntArray): List<Triple<RectF, Float, Int>> {
        val n = outShape[1]
        val stride = outShape[2]
        val out = Array(1) { Array(n) { FloatArray(stride) } }
        interpreter.run(input, out)

        val results = mutableListOf<Triple<RectF, Float, Int>>()
        for (row in out[0]) {
            val score = row[4]
            if (score < confThreshold) continue
            // 坐标可能是归一化(0~1)或像素值,自动判别
            val s = if (row[2] <= 1.5f) inputSize.toFloat() else 1f
            results.add(Triple(
                RectF(row[0] * s, row[1] * s, row[2] * s, row[3] * s),
                score, row[5].toInt()
            ))
        }
        return results
    }

    private fun parseRawWithNms(input: ByteBuffer, outShape: IntArray): List<Triple<RectF, Float, Int>> {
        val channels = outShape[1]   // 4 + 类别数
        val anchors = outShape[2]
        val out = Array(1) { Array(channels) { FloatArray(anchors) } }
        interpreter.run(input, out)
        val o = out[0]

        val candidates = mutableListOf<Triple<RectF, Float, Int>>()
        for (i in 0 until anchors) {
            var best = 0f
            var bestCls = 0
            for (c in 4 until channels) {
                if (o[c][i] > best) { best = o[c][i]; bestCls = c - 4 }
            }
            if (best < confThreshold) continue
            val s = if (o[2][i] <= 1.5f) inputSize.toFloat() else 1f
            val cx = o[0][i] * s; val cy = o[1][i] * s
            val w = o[2][i] * s; val h = o[3][i] * s
            candidates.add(Triple(
                RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2),
                best, bestCls
            ))
        }
        return nms(candidates)
    }

    private fun nms(boxes: List<Triple<RectF, Float, Int>>): List<Triple<RectF, Float, Int>> {
        val sorted = boxes.sortedByDescending { it.second }.toMutableList()
        val keep = mutableListOf<Triple<RectF, Float, Int>>()
        while (sorted.isNotEmpty()) {
            val top = sorted.removeAt(0)
            keep.add(top)
            sorted.removeAll { iou(top.first, it.first) > iouThreshold && it.third == top.third }
        }
        return keep
    }

    private fun iou(a: RectF, b: RectF): Float {
        val ix = maxOf(0f, minOf(a.right, b.right) - maxOf(a.left, b.left))
        val iy = maxOf(0f, minOf(a.bottom, b.bottom) - maxOf(a.top, b.top))
        val inter = ix * iy
        val union = a.width() * a.height() + b.width() * b.height() - inter
        return if (union <= 0f) 0f else inter / union
    }

    fun close() = interpreter.close()
}
