package com.example.welddetect

import android.content.Context
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog

data class DetectionSession(
    val labelBlock: String,
    val inspector: String,
) {
    val filePart: String
        get() = "${safeName(labelBlock)}_${safeName(inspector)}"

    companion object {
        fun safeName(value: String): String =
            value.replace(Regex("[^A-Za-z0-9\\u4e00-\\u9fa5_-]+"), "_").trim('_').ifEmpty { "weizhi" }
    }
}

fun Context.promptDetectionSession(
    title: String = "检测信息",
    onConfirmed: (DetectionSession) -> Unit,
) {
    val labelInput = EditText(this).apply {
        hint = "检测区块"
        isSingleLine = true
    }
    val inspectorInput = EditText(this).apply {
        hint = "检测人员"
        isSingleLine = true
    }
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(48, 12, 48, 0)
        addView(labelInput)
        addView(inspectorInput)
    }
    val dialog = AlertDialog.Builder(this)
        .setTitle(title)
        .setView(content)
        .setNegativeButton("取消", null)
        .setPositiveButton("开始", null)
        .create()

    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val labelBlock = labelInput.text.toString().trim()
            val inspector = inspectorInput.text.toString().trim()
            if (labelBlock.isEmpty()) {
                labelInput.error = "请输入检测区块"
                return@setOnClickListener
            }
            if (inspector.isEmpty()) {
                inspectorInput.error = "请输入检测人员"
                return@setOnClickListener
            }
            getSystemService(InputMethodManager::class.java)
                .hideSoftInputFromWindow(inspectorInput.windowToken, 0)
            dialog.dismiss()
            onConfirmed(DetectionSession(labelBlock, inspector))
        }
    }
    dialog.show()
}
