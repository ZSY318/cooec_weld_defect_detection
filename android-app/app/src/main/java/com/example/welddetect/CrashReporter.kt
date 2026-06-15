package com.example.welddetect

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashReporter {
    private val timeFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeCrash(appContext, thread, throwable) }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrash(context: Context, thread: Thread, throwable: Throwable) {
        val outDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "crash_logs")
        outDir.mkdirs()
        val stamp = timeFormat.format(Date())
        val file = File(outDir, "${stamp}_崩溃日志.txt")
        val stack = StringWriter().also {
            throwable.printStackTrace(PrintWriter(it))
        }.toString()

        file.writeText(
            buildString {
                appendLine("时间：$stamp")
                appendLine("线程：${thread.name}")
                appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("系统：Android ${Build.VERSION.RELEASE}，接口 ${Build.VERSION.SDK_INT}")
                appendLine("异常：${throwable.javaClass.name}")
                appendLine("信息：${throwable.message ?: "无"}")
                appendLine()
                appendLine("详细堆栈：")
                appendLine(stack)
            },
            Charsets.UTF_8,
        )
    }
}
