package com.example.welddetect

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** 图片检测页:从相册选图,推理后把检测框画在图上展示 */
class ImageActivity : AppCompatActivity() {

    private lateinit var imageView: ImageView
    private lateinit var statusText: TextView
    private var detector: Detector? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val fileTimeFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private var currentSession: DetectionSession? = null

    private val palette = intArrayOf(
        Color.rgb(255, 64, 64), Color.rgb(64, 160, 255), Color.rgb(64, 220, 120),
        Color.rgb(255, 180, 0), Color.rgb(200, 90, 255), Color.rgb(0, 220, 220),
    )

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { runDetection(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image)
        imageView = findViewById(R.id.imageView)
        statusText = findViewById(R.id.statusText)

        val modelName = intent.getStringExtra(ModelRepository.EXTRA_MODEL_NAME)
            ?: ModelRepository.getSelected(this)
        detector = if (modelName == null) {
            statusText.text = "未找到权重，请先导出"
            null
        } else try {
            Detector(this, modelName).also {
                statusText.text = "当前权重：${ModelDisplay.name(modelName)}"
            }
        } catch (e: Exception) {
            statusText.text = "模型加载失败: ${e.message}"
            null
        }

        findViewById<Button>(R.id.btnPick).setOnClickListener {
            promptDetectionSession("图片检测信息") {
                currentSession = it
                pickImage.launch("image/*")
            }
        }
    }

    private fun runDetection(uri: Uri) {
        val det = detector ?: return
        statusText.text = "检测中…"
        executor.execute {
            val src = ImageDecoder.decodeBitmap(
                ImageDecoder.createSource(contentResolver, uri)
            ) { decoder, _, _ -> decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE }

            // 过大的图先缩到 1280 以内,加快推理
            val maxSide = maxOf(src.width, src.height)
            val bitmap = if (maxSide > 1280) {
                val s = 1280f / maxSide
                Bitmap.createScaledBitmap(
                    src, (src.width * s).toInt(), (src.height * s).toInt(), true
                )
            } else src

            val t0 = System.currentTimeMillis()
            val results = det.detect(bitmap)
            val ms = System.currentTimeMillis() - t0

            val annotated = drawResults(bitmap, results)
            val saved = saveResult(annotated, results, currentSession)
            runOnUiThread {
                imageView.setImageBitmap(annotated)
                statusText.text = "检出 ${results.size} 处缺陷 | ${ms}ms\n已保存：${saved.first.name} / ${saved.second.name}"
            }
        }
    }

    private fun saveResult(
        bitmap: Bitmap,
        results: List<Detection>,
        session: DetectionSession?,
    ): Pair<File, File> {
        val outDir = File(
            File(
                File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "image_detections"),
                DetectionSession.safeName(session?.labelBlock ?: "未分组"),
            ),
            DetectionSession.safeName(session?.inspector ?: "未记录"),
        )
        outDir.mkdirs()
        val stamp = fileTimeFormat.format(Date())
        val imageFile = File(outDir, "${stamp}_图片预览.jpg")
        val summaryFile = File(outDir, "${stamp}_图片简表.csv")

        FileOutputStream(imageFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        summaryFile.writeText(
            "时间,检测区块,检测人员,检出数量,是否通过\n" +
                "$stamp,${csv(session?.labelBlock ?: "")},${csv(session?.inspector ?: "")},${results.size},${if (results.isEmpty()) "通过" else "未通过"}\n",
            Charsets.UTF_8
        )
        return imageFile to summaryFile
    }

    private fun csv(value: String): String =
        "\"${value.replace("\"", "\"\"")}\""

    private fun drawResults(bitmap: Bitmap, results: List<Detection>): Bitmap {
        val out = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val strokeW = maxOf(3f, out.width / 200f)
        val textSize = maxOf(28f, out.width / 30f)
        val boxPaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = strokeW }
        val textPaint = Paint().apply {
            color = Color.WHITE; this.textSize = textSize; isFakeBoldText = true
        }
        val bgPaint = Paint().apply { style = Paint.Style.FILL }

        for (d in results) {
            val color = palette[d.label.hashCode().mod(palette.size)]
            boxPaint.color = color
            bgPaint.color = color
            canvas.drawRect(d.box, boxPaint)
            val text = "${LabelDisplay.name(d.label)} ${"%.2f".format(d.score)}"
            val tw = textPaint.measureText(text)
            canvas.drawRect(
                d.box.left, d.box.top - textSize - 12f,
                d.box.left + tw + 16f, d.box.top, bgPaint
            )
            canvas.drawText(text, d.box.left + 8f, d.box.top - 8f, textPaint)
        }
        return out
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
        detector?.close()
    }
}
