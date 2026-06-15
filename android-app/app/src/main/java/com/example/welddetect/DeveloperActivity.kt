package com.example.welddetect

import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import java.io.File

class DeveloperActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "开发者模式"
        setContentView(buildContent())
    }

    private fun buildContent(): ScrollView {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp, 20.dp, 20.dp, 20.dp)
            setBackgroundColor(getColor(R.color.bg_dark))
        }

        val logs = loadCrashLogs()
        if (logs.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "暂无崩溃日志"
                setTextColor(getColor(R.color.text_secondary))
                textSize = 16f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            })
        } else {
            logs.forEach { log -> container.addView(logCard(log)) }
        }

        return ScrollView(this).apply { addView(container) }
    }

    private fun loadCrashLogs(): List<File> {
        val dir = File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "crash_logs")
        return dir.listFiles { file -> file.name.endsWith("_崩溃日志.txt") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    private fun logCard(log: File): MaterialCardView {
        val preview = log.readText(Charsets.UTF_8).lineSequence()
            .filter { it.isNotBlank() }
            .take(6)
            .joinToString("\n")

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 14.dp, 16.dp, 14.dp)
            addView(TextView(context).apply {
                text = log.name
                setTextColor(getColor(R.color.text_primary))
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(TextView(context).apply {
                text = preview
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
                setPadding(0, 8.dp, 0, 0)
            })
        }

        return MaterialCardView(this).apply {
            radius = 8.dp.toFloat()
            cardElevation = 0f
            setCardBackgroundColor(getColor(R.color.surface_card))
            strokeWidth = 1.dp
            strokeColor = getColor(R.color.divider)
            useCompatPadding = true
            isClickable = true
            addView(body)
            setOnClickListener { showLog(log) }
        }
    }

    private fun showLog(log: File) {
        val content = TextView(this).apply {
            text = log.readText(Charsets.UTF_8)
            setTextColor(getColor(R.color.text_primary))
            textSize = 12f
            setPadding(24.dp, 12.dp, 24.dp, 0)
        }
        AlertDialog.Builder(this)
            .setTitle("崩溃日志")
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("关闭", null)
            .show()
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}
