package com.example.welddetect

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** 主菜单页:选择检测模式与检测模型 */
class HomeActivity : AppCompatActivity() {

    private lateinit var txtModel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        findViewById<View>(R.id.btnCamera).setOnClickListener {
            startActivity(modelIntent(CameraActivity::class.java))
        }
        findViewById<View>(R.id.btnImage).setOnClickListener {
            startActivity(modelIntent(ImageActivity::class.java))
        }
        findViewById<View>(R.id.btnStandardTest).setOnClickListener {
            startActivity(modelIntent(StandardTestActivity::class.java))
        }
        findViewById<View>(R.id.btnResults).setOnClickListener {
            startActivity(Intent(this, ResultHistoryActivity::class.java))
        }

        txtModel = findViewById(R.id.txtModel)
        txtModel.setOnClickListener { showModelPicker() }

        val aboutButton = findViewById<View>(R.id.btnAbout)
        aboutButton.setOnClickListener {
            val current = ModelRepository.getSelected(this)?.let { ModelDisplay.name(it) } ?: "未安装"
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.app_name))
                .setMessage(
                    "焊缝缺陷本地智能检测\n\n" +
                    "当前权重：$current\n" +
                    "推理方式：手机端本地识别\n" +
                    "版本:1.0"
                )
                .setPositiveButton("确定", null)
                .show()
        }
        aboutButton.setOnLongClickListener {
            startActivity(Intent(this, DeveloperActivity::class.java))
            true
        }
    }

    override fun onResume() {
        super.onResume()
        updateModelLabel()
    }

    private fun updateModelLabel() {
        val current = ModelRepository.getSelected(this)
        txtModel.text = if (current == null) "未安装权重" else "检测权重：${ModelDisplay.name(current)}  ▾"
    }

    private fun modelIntent(target: Class<*>): Intent =
        Intent(this, target).apply {
            ModelRepository.getSelected(this@HomeActivity)?.let {
                putExtra(ModelRepository.EXTRA_MODEL_NAME, it)
            }
        }

    private fun showModelPicker() {
        val models = ModelRepository.listModels(this)
        if (models.isEmpty()) {
            Toast.makeText(this, "未找到可用权重，请先导出", Toast.LENGTH_LONG).show()
            return
        }
        val current = ModelRepository.getSelected(this)
        val checked = models.indexOf(current).coerceAtLeast(0)
        val displayNames = models.map { ModelDisplay.name(it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择检测权重")
            .setSingleChoiceItems(displayNames, checked) { dialog, which ->
                ModelRepository.setSelected(this, models[which])
                updateModelLabel()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
