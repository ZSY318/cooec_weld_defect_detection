package com.example.welddetect

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class StandardTestActivity : AppCompatActivity() {

    private data class ClassSummary(
        val label: String,
        var count: Int = 0,
        var maxScore: Float = 0f,
        var firstTime: String = "",
        var lastTime: String = "",
    )

    private lateinit var formPanel: LinearLayout
    private lateinit var resultPanel: LinearLayout
    private lateinit var labelInput: EditText
    private lateinit var inspectorInput: EditText
    private lateinit var statusText: TextView
    private lateinit var resultStatusText: TextView
    private lateinit var summaryText: TextView
    private lateinit var previewImage: ImageView
    private lateinit var previewVideo: VideoView
    private lateinit var progressBar: ProgressBar
    private lateinit var summaryTable: TableLayout
    private lateinit var btnPrevFrame: Button
    private lateinit var btnPlayPause: Button
    private lateinit var btnNextFrame: Button
    private lateinit var btnShareResult: Button
    private var detector: Detector? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val fileTimeFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    // 视频按 outputFps 抽帧+编码(回放流畅), 但 YOLO 推理只按 detectFps 跑,
    // 中间帧复用上一次的检测框 -> 推理次数减到 detectFps/outputFps, 大幅提速
    private val outputFps = 24
    private val detectFps = 8
    // 每个置信缺陷首帧即计入(minHits=1), 仅用中心距离对相邻帧的同一缺陷去重
    private val stabilizer = DetectionStabilizer(
        minHits = 1,
        instantThreshold = 0.45f,
        maxMisses = 2,
        displayMisses = 0,
        matchCenterRatio = 0.8f,
    )
    private var resultVideoFile: File? = null
    private var resultFiles: List<File> = emptyList()

    private val palette = intArrayOf(
        Color.rgb(255, 64, 64), Color.rgb(64, 160, 255), Color.rgb(64, 220, 120),
        Color.rgb(255, 180, 0), Color.rgb(200, 90, 255), Color.rgb(0, 220, 220),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_standard_test)

        formPanel = findViewById(R.id.formPanel)
        resultPanel = findViewById(R.id.resultPanel)
        labelInput = findViewById(R.id.inputLabelBlock)
        inspectorInput = findViewById(R.id.inputInspector)
        statusText = findViewById(R.id.statusText)
        resultStatusText = findViewById(R.id.resultStatusText)
        summaryText = findViewById(R.id.summaryText)
        previewImage = findViewById(R.id.previewImage)
        previewVideo = findViewById(R.id.previewVideo)
        progressBar = findViewById(R.id.progressBar)
        summaryTable = findViewById(R.id.summaryTable)
        btnPrevFrame = findViewById(R.id.btnPrevFrame)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnNextFrame = findViewById(R.id.btnNextFrame)
        btnShareResult = findViewById(R.id.btnShareResult)

        val modelName = defaultModelName()
        detector = if (modelName == null) {
            statusText.text = "未找到默认模型，请先导出模型"
            null
        } else try {
            Detector(this, modelName).also {
                statusText.text = "当前权重：${ModelDisplay.name(modelName)}"
            }
        } catch (e: Exception) {
            statusText.text = "模型加载失败：${e.message}"
            null
        }
        renderSummaryTable(emptyList())

        findViewById<Button>(R.id.btnRunTest).setOnClickListener { runStandardTest() }
        btnPrevFrame.setOnClickListener {
            if (previewVideo.isShown) previewVideo.seekTo((previewVideo.currentPosition - 3000).coerceAtLeast(0))
        }
        btnNextFrame.setOnClickListener {
            if (previewVideo.isShown) previewVideo.seekTo(previewVideo.currentPosition + 3000)
        }
        btnPlayPause.setOnClickListener {
            if (!previewVideo.isShown) return@setOnClickListener
            if (previewVideo.isPlaying) previewVideo.pause() else previewVideo.start()
        }
        btnShareResult.setOnClickListener { shareResults() }
        setPlaybackEnabled(false)
    }

    private fun defaultModelName(): String? {
        val models = ModelRepository.listModels(this)
        intent.getStringExtra(ModelRepository.EXTRA_MODEL_NAME)
            ?.takeIf { it in models }
            ?.let { return it }
        return models.firstOrNull { it.contains("WeldSpatter", ignoreCase = true) }
            ?: models.firstOrNull { it.contains("Default", ignoreCase = true) }
            ?: ModelRepository.getSelected(this)
    }

    private fun runStandardTest() {
        val det = detector ?: return
        val labelBlock = labelInput.text.toString().trim()
        val inspector = inspectorInput.text.toString().trim()
        if (labelBlock.isEmpty() || inspector.isEmpty()) {
            statusText.text = "请先输入检测区块和检测人员"
            return
        }

        currentFocus?.let {
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0)
            it.clearFocus()
        }
        formPanel.visibility = View.GONE
        resultPanel.visibility = View.VISIBLE
        previewVideo.stopPlayback()
        previewVideo.visibility = View.GONE
        previewImage.visibility = View.VISIBLE
        resultStatusText.text = "后台检测中：准备解析视频..."
        summaryText.text = ""
        progressBar.progress = 0
        stabilizer.reset()
        setPlaybackEnabled(false)
        renderSummaryTable(emptyList())

        executor.execute {
            val stamp = fileTimeFormat.format(Date())
            val summaryCsv = mutableListOf("类别,次数,最高置信度,首次时间,末次时间,是否缺陷")
            val detailCsv = mutableListOf("时间,类别,置信度,左,上,右,下")
            val classSummaries = linkedMapOf<String, ClassSummary>()
            var bestPreview: Bitmap? = null
            var bestPreviewDefects = -1
            var totalDefects = 0
            var sampledFrames = 0

            val outDir = File(
                File(
                    File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "standard_tests"),
                    safeName(labelBlock),
                ),
                safeName(inspector),
            )
            outDir.mkdirs()
            val summaryFile = File(outDir, "${stamp}_简表.csv")
            val detailFile = File(outDir, "${stamp}_明细.csv")
            val previewFile = File(outDir, "${stamp}_预览.jpg")
            val videoFile = File(outDir, "${stamp}_标注视频.mp4")

            var encoder: FrameVideoEncoder? = null
            try {
                val retriever = MediaMetadataRetriever()
                try {
                    assets.openFd("test.mp4").use { fd ->
                        retriever.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                    }
                    val durationMs = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_DURATION
                    )?.toLongOrNull() ?: 0L
                    val stepMs = 1000L / outputFps
                    val detectStepMs = 1000L / detectFps
                    var lastDetectMs = -detectStepMs // 保证第 0 帧即检测
                    var lastDetections: List<Detection> = emptyList()
                    var tMs = 0L
                    while (tMs <= durationMs) {
                        val bitmap = retriever.getFrameAtTime(
                            tMs * 1000,
                            MediaMetadataRetriever.OPTION_CLOSEST
                        )
                        if (bitmap != null) {
                            val scaled = bitmap.scaleForDetection()
                            if (scaled !== bitmap) bitmap.recycle()
                            val timeText = "%.2f".format(Locale.US, tMs / 1000f)

                            // 按 detectFps 决定本帧是否真正推理;否则复用上一次的框
                            val doDetect = tMs - lastDetectMs >= detectStepMs
                            val detections: List<Detection>
                            if (doDetect) {
                                val rawDetections = det.detect(scaled)
                                val stableFrame = stabilizer.update(rawDetections)
                                detections = stableFrame.detections
                                lastDetectMs = tMs
                                lastDetections = detections

                                for (d in stableFrame.newlyConfirmed) {
                                    classSummaries.getOrPut(d.label) { ClassSummary(d.label) }.apply {
                                        count += 1
                                        if (d.score > maxScore) maxScore = d.score
                                        if (firstTime.isEmpty()) firstTime = timeText
                                        lastTime = timeText
                                    }
                                    detailCsv += listOf(
                                        timeText,
                                        csv(LabelDisplay.name(d.label)),
                                        "%.4f".format(Locale.US, d.score),
                                        "%.1f".format(Locale.US, d.box.left),
                                        "%.1f".format(Locale.US, d.box.top),
                                        "%.1f".format(Locale.US, d.box.right),
                                        "%.1f".format(Locale.US, d.box.bottom),
                                    ).joinToString(",")
                                }
                                totalDefects += stableFrame.newlyConfirmed.count { it.isDefect() }
                            } else {
                                detections = lastDetections
                            }

                            val annotated = drawResults(scaled, detections)
                            if (scaled !== annotated) scaled.recycle()
                            val defectCount = detections.count { it.isDefect() }

                            if (encoder == null) {
                                val (w, h) = encodeSize(annotated.width, annotated.height)
                                encoder = FrameVideoEncoder(w, h, outputFps, videoFile)
                            }
                            encoder.encode(annotated)

                            if (doDetect && defectCount > bestPreviewDefects) {
                                bestPreview = annotated.copy(Bitmap.Config.ARGB_8888, false)
                                bestPreviewDefects = defectCount
                            }
                            annotated.recycle()
                            sampledFrames++
                            val progress = if (durationMs > 0) {
                                ((tMs * 100) / durationMs).toInt().coerceIn(0, 100)
                            } else 0

                            runOnUiThread {
                                // 处理阶段只更新进度,不逐帧刷预览;完成后再整段播放视频
                                progressBar.progress = progress
                                resultStatusText.text =
                                    "后台检测中：${progress}% | ${timeText}s | 当前帧检出 ${defectCount} 处"
                            }
                        }
                        tMs += stepMs
                    }
                } finally {
                    retriever.release()
                }
                encoder?.finish()

                val sortedSummaries = classSummaries.values.sortedWith(
                    compareByDescending<ClassSummary> { it.isDefect() }.thenByDescending { it.count }
                )
                for (summary in sortedSummaries) {
                    summaryCsv += listOf(
                        csv(LabelDisplay.name(summary.label)),
                        summary.count.toString(),
                        "%.4f".format(Locale.US, summary.maxScore),
                        summary.firstTime,
                        summary.lastTime,
                        if (summary.isDefect()) "是" else "否",
                    ).joinToString(",")
                }
                summaryFile.writeText(summaryCsv.joinToString("\n"), Charsets.UTF_8)
                detailFile.writeText(detailCsv.joinToString("\n"), Charsets.UTF_8)
                bestPreview?.saveJpeg(previewFile)
                resultVideoFile = videoFile
                resultFiles = listOf(videoFile, summaryFile, detailFile, previewFile)
                    .filter { it.exists() }

                val passed = totalDefects == 0
                runOnUiThread {
                    progressBar.progress = 100
                    resultStatusText.text =
                        "识别完成：${if (passed) "通过" else "未通过"} | 抽帧 $sampledFrames | 缺陷 $totalDefects"
                    summaryText.text =
                        "标注视频：${videoFile.name}\n简表：${summaryFile.name}\n明细：${detailFile.name}"
                    renderSummaryTable(sortedSummaries)
                    btnShareResult.isEnabled = resultFiles.isNotEmpty()
                    startResultVideo(videoFile)
                }
            } catch (e: Exception) {
                runCatching { encoder?.finish() }
                runOnUiThread {
                    resultStatusText.text = "识别失败：${e.message}"
                }
            }
        }
    }

    private fun startResultVideo(file: File) {
        if (!file.exists()) {
            resultStatusText.text = "${resultStatusText.text}（标注视频生成失败）"
            return
        }
        previewImage.visibility = View.GONE
        previewVideo.visibility = View.VISIBLE
        previewVideo.setVideoPath(file.absolutePath)
        previewVideo.setOnPreparedListener { mp ->
            mp.isLooping = true
            previewVideo.start()
            setPlaybackEnabled(true)
        }
        previewVideo.setOnErrorListener { _, what, extra ->
            resultStatusText.text = "标注视频播放失败（$what/$extra）"
            true
        }
    }

    private fun setPlaybackEnabled(enabled: Boolean) {
        btnPrevFrame.isEnabled = enabled
        btnPlayPause.isEnabled = enabled
        btnNextFrame.isEnabled = enabled
        btnShareResult.isEnabled = enabled
    }

    private fun shareResults() {
        val files = resultFiles.filter { it.exists() }
        if (files.isEmpty()) {
            Toast.makeText(this, "暂无可分享的结果文件", Toast.LENGTH_SHORT).show()
            return
        }
        val uris = ArrayList<Uri>(files.map {
            FileProvider.getUriForFile(this, "${packageName}.fileprovider", it)
        })
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_SUBJECT, "焊缝标准测试结果")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "分享检测结果"))
    }

    /** 编码尺寸：长边限到 1280 以内并取偶数，兼顾编码速度与编码器能力。 */
    private fun encodeSize(w: Int, h: Int): Pair<Int, Int> {
        val maxSide = 1280
        var ow = w
        var oh = h
        val m = maxOf(w, h)
        if (m > maxSide) {
            val s = maxSide.toFloat() / m
            ow = (w * s).toInt()
            oh = (h * s).toInt()
        }
        ow -= ow % 2
        oh -= oh % 2
        return ow.coerceAtLeast(2) to oh.coerceAtLeast(2)
    }

    private fun Bitmap.scaleForDetection(): Bitmap {
        val maxSide = maxOf(width, height)
        if (maxSide <= 1600) return this
        val scale = 1600f / maxSide
        return Bitmap.createScaledBitmap(this, (width * scale).toInt(), (height * scale).toInt(), true)
    }

    private fun drawResults(bitmap: Bitmap, results: List<Detection>): Bitmap {
        val out = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val strokeW = maxOf(6f, out.width / 110f)
        val textSize = maxOf(42f, out.width / 18f)
        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokeW
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            isFakeBoldText = true
        }
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        for (d in results) {
            val color = palette[d.label.hashCode().mod(palette.size)]
            boxPaint.color = color
            bgPaint.color = color
            bgPaint.alpha = 140 // 半透明标签底
            canvas.drawRect(d.box, boxPaint)
            val text = "${LabelDisplay.name(d.label)} ${"%.2f".format(Locale.US, d.score)}"
            val tw = textPaint.measureText(text)
            val labelTop = if (d.box.top > textSize + 24f) {
                d.box.top - textSize - 18f
            } else {
                minOf(out.height - textSize - 18f, d.box.top + strokeW)
            }
            val labelBottom = minOf(out.height.toFloat(), labelTop + textSize + 18f)
            val labelRight = minOf(out.width.toFloat(), d.box.left + tw + 24f)
            canvas.drawRect(d.box.left, labelTop, labelRight, labelBottom, bgPaint)
            canvas.drawText(text, d.box.left + 12f, labelBottom - 10f, textPaint)
        }
        return out
    }

    private fun Bitmap.saveJpeg(file: File) {
        FileOutputStream(file).use { out ->
            compress(Bitmap.CompressFormat.JPEG, 94, out)
        }
    }

    private fun safeName(value: String): String =
        value.replace(Regex("[^A-Za-z0-9\\u4e00-\\u9fa5_-]+"), "_").trim('_').ifEmpty { "weizhi" }

    private fun csv(value: String): String =
        "\"${value.replace("\"", "\"\"")}\""

    private fun Detection.isDefect(): Boolean =
        !label.equals("Normal Weld-NW", ignoreCase = true) &&
            !label.contains("Normal Weld", ignoreCase = true)

    private fun ClassSummary.isDefect(): Boolean =
        !label.equals("Normal Weld-NW", ignoreCase = true) &&
            !label.contains("Normal Weld", ignoreCase = true)

    private fun renderSummaryTable(rows: List<ClassSummary>) {
        summaryTable.removeAllViews()
        summaryTable.addView(tableRow("类别", "次数", "最高置信度", true))
        for (row in rows) {
            summaryTable.addView(tableRow(LabelDisplay.name(row.label), row.count.toString(), "%.2f".format(Locale.US, row.maxScore), false))
        }
    }

    private fun tableRow(first: String, second: String, third: String, header: Boolean): TableRow {
        return TableRow(this).apply {
            addView(tableCell(first, header, 1.5f))
            addView(tableCell(second, header, 0.8f))
            addView(tableCell(third, header, 1f))
        }
    }

    private fun tableCell(text: String, header: Boolean, weight: Float): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(getColor(if (header) R.color.text_primary else R.color.text_secondary))
            textSize = if (header) 13f else 12f
            gravity = Gravity.CENTER
            setPadding(8, 8, 8, 8)
            if (header) setTypeface(typeface, Typeface.BOLD)
            layoutParams = TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, weight)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { previewVideo.stopPlayback() }
        executor.shutdown()
        detector?.close()
    }
}
