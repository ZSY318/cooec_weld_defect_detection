package com.example.welddetect

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.Environment
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class CameraActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var statusText: TextView
    private lateinit var resultImage: ImageView
    private lateinit var btnDetect: Button
    private lateinit var btnSaveCurrent: Button
    private var detector: Detector? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val fileTimeFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    // 按需检测:预览常态不推理(任意弱机都流畅),点"检测"才对当前帧跑一次推理。
    @Volatile private var captureRequested = false
    @Volatile private var frozen = false
    private var overlayConfigured = false
    private var latestBitmap: Bitmap? = null
    private var latestResults: List<Detection> = emptyList()
    private var currentSession: DetectionSession? = null

    // 归一化中心引导框:焊缝需落在此框内才允许判断
    private val guideFrac = RectF(0.18f, 0.30f, 0.82f, 0.70f)

    private val palette = intArrayOf(
        Color.rgb(255, 64, 64), Color.rgb(64, 160, 255), Color.rgb(64, 220, 120),
        Color.rgb(255, 180, 0), Color.rgb(200, 90, 255), Color.rgb(0, 220, 220),
    )

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else Toast.makeText(this, "需要相机权限才能检测", Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)
        previewView = findViewById(R.id.previewView)
        overlay = findViewById(R.id.overlay)
        statusText = findViewById(R.id.statusText)
        resultImage = findViewById(R.id.resultImage)
        btnDetect = findViewById(R.id.btnDetect)
        btnSaveCurrent = findViewById(R.id.btnSaveCurrent)
        btnSaveCurrent.setOnClickListener { saveCurrentResult() }
        btnDetect.setOnClickListener { onDetectClicked() }

        val modelName = intent.getStringExtra(ModelRepository.EXTRA_MODEL_NAME)
            ?: ModelRepository.getSelected(this)
        detector = if (modelName == null) {
            statusText.text = "未找到模型，请先导出模型"
            null
        } else try {
            Detector(this, modelName).also {
                statusText.text = "当前权重：${ModelDisplay.name(modelName)}"
            }
        } catch (e: Exception) {
            statusText.text = "模型加载失败: ${e.message}"
            null
        }

        promptDetectionSession("实时检测信息") {
            currentSession = it
            openCameraAfterSession()
        }
    }

    private fun openCameraAfterSession() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { it.setAnalyzer(analysisExecutor) { image -> analyze(image) } }

            provider.unbindAll()
            provider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onDetectClicked() {
        if (frozen) { resumeLive(); return }
        if (detector == null) {
            Toast.makeText(this, "模型未就绪", Toast.LENGTH_SHORT).show()
            return
        }
        statusText.text = "检测中…"
        btnDetect.isEnabled = false
        captureRequested = true // 下一帧到达时执行一次推理
    }

    private fun resumeLive() {
        frozen = false
        captureRequested = false
        resultImage.visibility = android.view.View.GONE
        btnDetect.text = "检测"
        btnDetect.isEnabled = true
        btnSaveCurrent.isEnabled = false
        overlay.setGuide(guideFrac) // 沿用首帧配置好的图像尺寸
        statusText.text = "将焊缝置于中心框内后点击检测"
    }

    /**
     * 预览常态下不推理:仅在首帧配置一次引导框,其余帧直接丢弃 -> 零持续算力,任意弱机都流畅。
     * 仅当用户点了"检测"(captureRequested) 时,才对这一帧跑一次推理并做"焊缝居中"闸门判断。
     */
    private fun analyze(image: ImageProxy) {
        val rot = image.imageInfo.rotationDegrees
        if (!overlayConfigured) {
            overlayConfigured = true
            val w = if (rot == 90 || rot == 270) image.height else image.width
            val h = if (rot == 90 || rot == 270) image.width else image.height
            runOnUiThread { overlay.setGuide(guideFrac, w, h) }
        }

        if (!captureRequested || frozen) { image.close(); return }
        captureRequested = false
        val det = detector ?: run { image.close(); return }

        val t0 = System.currentTimeMillis()
        val bitmap = image.use { it.toBitmap(rot) }
        val raw = det.detect(bitmap)
        val ms = System.currentTimeMillis() - t0

        val welds = raw.filter { isWeld(it.label) }
        val centeredWeld = welds.any { centerInGuide(it.box, bitmap.width, bitmap.height) }

        runOnUiThread {
            btnDetect.isEnabled = true
            when {
                welds.isEmpty() ->
                    statusText.text = "未检测到焊缝，请对准焊缝 (${ms}ms)"
                !centeredWeld ->
                    statusText.text = "请将焊缝移到中心框内再检测 (${ms}ms)"
                else -> {
                    frozen = true
                    latestBitmap = bitmap
                    latestResults = raw
                    val defects = raw.filter { isDefect(it.label) }
                    resultImage.setImageBitmap(drawResults(bitmap, raw))
                    resultImage.visibility = android.view.View.VISIBLE
                    overlay.clearGuide()
                    btnDetect.text = "继续"
                    btnSaveCurrent.isEnabled = true
                    statusText.text = if (defects.isEmpty()) {
                        "焊缝合格：未见缺陷 (${ms}ms)"
                    } else {
                        "未通过：检出 ${defects.size} 处缺陷 (${ms}ms)"
                    }
                }
            }
        }
    }

    /** 焊缝(或任何缺陷)的框中心是否落在归一化中心引导框内 */
    private fun centerInGuide(box: RectF, srcW: Int, srcH: Int): Boolean {
        if (srcW <= 0 || srcH <= 0) return false
        val cx = box.centerX() / srcW
        val cy = box.centerY() / srcH
        return guideFrac.contains(cx, cy)
    }

    private fun isWeld(label: String): Boolean =
        label.equals("weld", ignoreCase = true) || label.contains("weld", ignoreCase = true)

    private fun isDefect(label: String): Boolean = !isWeld(label)

    private fun saveCurrentResult() {
        val session = currentSession
        if (session == null) {
            Toast.makeText(this, "请先输入检测区块和检测人员", Toast.LENGTH_SHORT).show()
            return
        }
        val bitmap = latestBitmap
        if (bitmap == null || !frozen) {
            Toast.makeText(this, "请先完成一次检测再保存", Toast.LENGTH_SHORT).show()
            return
        }
        val defectCount = latestResults.count { isDefect(it.label) }
        val stamp = fileTimeFormat.format(Date())
        val outDir = File(
            File(
                File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "camera_detections"),
                DetectionSession.safeName(session.labelBlock),
            ),
            DetectionSession.safeName(session.inspector),
        )
        outDir.mkdirs()
        val imageFile = File(outDir, "${stamp}_相机预览.jpg")
        val summaryFile = File(outDir, "${stamp}_相机简表.csv")
        val annotated = drawResults(bitmap, latestResults)

        FileOutputStream(imageFile).use { out ->
            annotated.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        summaryFile.writeText(
            "时间,检测区块,检测人员,检出数量,是否通过\n" +
                "$stamp,${csv(session.labelBlock)},${csv(session.inspector)},$defectCount,${if (defectCount == 0) "通过" else "未通过"}\n",
            Charsets.UTF_8
        )
        Toast.makeText(this, "已保存：${imageFile.name}", Toast.LENGTH_LONG).show()
    }

    private fun drawResults(bitmap: Bitmap, results: List<Detection>): Bitmap {
        val out = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val strokeW = maxOf(3f, out.width / 200f)
        val textSize = maxOf(28f, out.width / 30f)
        val boxPaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = strokeW }
        val textPaint = Paint().apply {
            color = Color.WHITE
            this.textSize = textSize
            isFakeBoldText = true
        }
        val bgPaint = Paint().apply { style = Paint.Style.FILL }

        for (d in results) {
            val color = palette[d.label.hashCode().mod(palette.size)]
            boxPaint.color = color
            bgPaint.color = color
            bgPaint.alpha = 140 // 半透明标签底
            canvas.drawRect(d.box, boxPaint)
            val text = "${LabelDisplay.name(d.label)} ${"%.2f".format(Locale.US, d.score)}"
            val tw = textPaint.measureText(text)
            canvas.drawRect(
                d.box.left, d.box.top - textSize - 12f,
                d.box.left + tw + 16f, d.box.top, bgPaint
            )
            canvas.drawText(text, d.box.left + 8f, d.box.top - 8f, textPaint)
        }
        return out
    }

    private fun csv(value: String): String =
        "\"${value.replace("\"", "\"\"")}\""

    private fun ImageProxy.toBitmap(rotationDegrees: Int): Bitmap {
        val plane = planes[0]
        val rowStridePx = plane.rowStride / plane.pixelStride
        var bmp = Bitmap.createBitmap(rowStridePx, height, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(plane.buffer)
        if (rowStridePx != width) {
            bmp = Bitmap.createBitmap(bmp, 0, 0, width, height)
        }
        if (rotationDegrees != 0) {
            val m = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        return bmp
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
        detector?.close()
    }
}
