package com.example.welddetect

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.card.MaterialCardView
import java.io.File

class ResultHistoryActivity : AppCompatActivity() {

    private data class SavedRecord(
        val type: String,
        val block: String,
        val inspector: String,
        val file: File,
        val previewFile: File?,
        val modifiedTime: Long,
    )

    private data class ResultGroup(
        val type: String,
        val block: String,
        val inspector: String,
        val records: List<SavedRecord>,
    )

    private lateinit var listContainer: LinearLayout
    private var groups: List<ResultGroup> = emptyList()
    private val expandedKeys = mutableSetOf<String>()
    private val groupPreviewSize = 20

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "保存结果"
        groups = loadRecords()
            .groupBy { groupKey(it.type, it.block, it.inspector) }
            .map { (_, records) ->
                val sorted = records.sortedByDescending { it.modifiedTime }
                val first = sorted.first()
                ResultGroup(first.type, first.block, first.inspector, sorted)
            }
            .sortedByDescending { it.records.first().modifiedTime }
        setContentView(buildContent())
        renderGroups()
    }

    private fun buildContent(): ScrollView {
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp, 20.dp, 20.dp, 20.dp)
            setBackgroundColor(getColor(R.color.bg_dark))
        }
        return ScrollView(this).apply { addView(listContainer) }
    }

    private fun renderGroups() {
        listContainer.removeAllViews()
        if (groups.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = "暂无保存结果"
                setTextColor(getColor(R.color.text_secondary))
                textSize = 16f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            })
            return
        }

        groups.forEach { group ->
            listContainer.addView(groupCard(group))
            if (expandedKeys.contains(group.key())) {
                group.records.take(groupPreviewSize).forEach { record ->
                    listContainer.addView(recordCard(record))
                }
                if (group.records.size > groupPreviewSize) {
                    listContainer.addView(TextView(this).apply {
                        text = "本组还有 ${group.records.size - groupPreviewSize} 条，请按区块或人员分组查看"
                        setTextColor(getColor(R.color.text_secondary))
                        textSize = 12f
                        gravity = Gravity.CENTER
                        setPadding(0, 4.dp, 0, 12.dp)
                    })
                }
            }
        }
    }

    private fun groupCard(group: ResultGroup): MaterialCardView {
        val expanded = expandedKeys.contains(group.key())
        val latestName = group.records.first().file.name
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 14.dp, 16.dp, 14.dp)
            addView(TextView(context).apply {
                text = "${if (expanded) "收起" else "展开"}  ${group.type}"
                setTextColor(getColor(R.color.text_primary))
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(TextView(context).apply {
                text = "区块：${group.block}    人员：${group.inspector}\n记录：${group.records.size} 条    最新：$latestName"
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
                setPadding(0, 8.dp, 0, 0)
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, 10.dp, 0, 0)
                addView(actionButton("分享本组") { shareGroup(group) })
            })
        }

        return MaterialCardView(this).apply {
            radius = 8.dp.toFloat()
            cardElevation = 0f
            setCardBackgroundColor(getColor(R.color.surface_card))
            strokeWidth = 1.dp
            strokeColor = getColor(R.color.accent)
            useCompatPadding = true
            isClickable = true
            addView(body)
            setOnClickListener {
                val key = group.key()
                if (expandedKeys.contains(key)) expandedKeys.remove(key) else expandedKeys.add(key)
                renderGroups()
            }
        }
    }

    private fun recordCard(record: SavedRecord): MaterialCardView {
        val summaryText = record.file.readLines(Charsets.UTF_8)
            .asSequence()
            .filter { it.isNotBlank() }
            .take(4)
            .joinToString("\n")

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32.dp, 10.dp, 16.dp, 12.dp)
            addView(TextView(context).apply {
                text = record.file.name
                setTextColor(getColor(R.color.text_primary))
                textSize = 14f
            })
            addView(TextView(context).apply {
                text = summaryText
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
                setPadding(0, 6.dp, 0, 0)
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, 8.dp, 0, 0)
                addView(actionButton("查看") { showRecord(record) })
                addView(actionButton("分享") { shareRecord(record) })
            })
        }

        return MaterialCardView(this).apply {
            radius = 8.dp.toFloat()
            cardElevation = 0f
            setCardBackgroundColor(getColor(R.color.surface_card))
            strokeWidth = 1.dp
            strokeColor = getColor(R.color.divider)
            useCompatPadding = true
            addView(body)
        }
    }

    private fun actionButton(text: String, action: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            minHeight = 0
            minWidth = 0
            setPadding(14.dp, 6.dp, 14.dp, 6.dp)
            setOnClickListener { action() }
        }

    private fun loadRecords(): List<SavedRecord> {
        val resultDirs = listOf(
            "标准测试" to File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "standard_tests"),
            "图片检测" to File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "image_detections"),
            "实时检测" to File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "camera_detections"),
        )
        return resultDirs.flatMap { (type, dir) ->
            if (!dir.exists()) return@flatMap emptyList()
            dir.walkTopDown()
                .filter { file -> file.isFile && (file.name.endsWith("_summary.csv") || file.name.endsWith("_简表.csv")) }
                .map { summary ->
                    val parent = summary.parentFile ?: dir
                    val parts = dir.toPath().relativize(parent.toPath()).map { it.toString() }.toList()
                    SavedRecord(
                        type = type,
                        block = parts.getOrNull(0) ?: "未分组",
                        inspector = parts.getOrNull(1) ?: "未记录",
                        file = summary,
                        previewFile = findPreview(summary),
                        modifiedTime = summary.lastModified(),
                    )
                }
                .toList()
        }
    }

    private fun findPreview(summary: File): File? {
        val exactNames = listOf(
            summary.name.replace("_summary.csv", "_preview.jpg"),
            summary.name.replace("_summary.csv", "_图片预览.jpg"),
            summary.name.replace("_summary.csv", "_相机预览.jpg"),
            summary.name.replace("_简表.csv", "_预览.jpg"),
            summary.name.replace("_简表.csv", "_图片预览.jpg"),
            summary.name.replace("_简表.csv", "_相机预览.jpg"),
        )
        exactNames.map { File(summary.parentFile, it) }.firstOrNull { it.exists() }?.let { return it }
        val prefix = summary.name.substringBefore("_summary.csv").substringBefore("_简表.csv")
        return summary.parentFile
            ?.listFiles { file ->
                file.name.startsWith(prefix) &&
                    (file.name.endsWith("_preview.jpg") || file.name.endsWith("_预览.jpg") ||
                        file.name.endsWith("_图片预览.jpg") || file.name.endsWith("_相机预览.jpg"))
            }
            ?.firstOrNull()
    }

    private fun relatedFiles(record: SavedRecord): List<File> {
        val files = linkedSetOf<File>()
        files += record.file
        record.previewFile?.let { files += it }
        val dir = record.file.parentFile ?: return files.toList()
        val prefix = record.file.name.substringBefore("_summary.csv").substringBefore("_简表.csv")
        dir.listFiles { file ->
            file.isFile && file.name.startsWith(prefix) &&
                (file.name.endsWith(".csv") || file.name.endsWith(".jpg") || file.name.endsWith(".txt"))
        }?.forEach { files += it }
        return files.filter { it.exists() }
    }

    private fun shareRecord(record: SavedRecord) {
        shareFiles(relatedFiles(record), "${record.type} ${record.block} ${record.inspector}")
    }

    private fun shareGroup(group: ResultGroup) {
        val files = group.records.flatMap { relatedFiles(it) }.distinctBy { it.absolutePath }
        shareFiles(files, "${group.type} ${group.block} ${group.inspector}")
    }

    private fun shareFiles(files: List<File>, title: String) {
        if (files.isEmpty()) {
            Toast.makeText(this, "没有可分享的文件", Toast.LENGTH_SHORT).show()
            return
        }
        val uris = ArrayList<Uri>(files.map { file ->
            FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        })
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "分享检测文件"))
    }

    private fun showRecord(record: SavedRecord) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp, 12.dp, 24.dp, 0)
        }
        record.previewFile?.takeIf { it.exists() }?.let { file ->
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            if (bitmap != null) {
                content.addView(ImageView(this).apply {
                    setImageBitmap(bitmap)
                    adjustViewBounds = true
                    maxHeight = 360.dp
                    scaleType = ImageView.ScaleType.FIT_CENTER
                })
            }
        }
        content.addView(TextView(this).apply {
            text = record.file.readText(Charsets.UTF_8)
            setTextColor(getColor(R.color.text_primary))
            textSize = 12f
            setPadding(0, 12.dp, 0, 0)
        })

        AlertDialog.Builder(this)
            .setTitle(record.type)
            .setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton("关闭", null)
            .setPositiveButton("分享") { _, _ -> shareRecord(record) }
            .show()
    }

    private fun ResultGroup.key(): String = groupKey(type, block, inspector)

    private fun groupKey(type: String, block: String, inspector: String): String =
        "$type|$block|$inspector"

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}
