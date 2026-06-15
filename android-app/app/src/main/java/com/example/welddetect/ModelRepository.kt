package com.example.welddetect

import android.content.Context

/**
 * 模型仓库:管理 assets/models/ 下的所有 TFLite 模型。
 *
 * 约定:每个模型由一对同名文件构成 —— <模型名>.tflite + <模型名>.txt(类别标签)。
 * 新增模型只需把这对文件放进 assets/models/ 并重新编译,无需改代码。
 * 当前选中的模型用 SharedPreferences 持久化。
 */
object ModelRepository {
    const val EXTRA_MODEL_NAME = "com.example.welddetect.EXTRA_MODEL_NAME"

    private const val PREFS = "weld_prefs"
    private const val KEY_MODEL = "selected_model"

    /** 列出所有可用模型名(不含扩展名),按名称排序 */
    fun listModels(context: Context): List<String> =
        context.assets.list("models")
            ?.filter { it.endsWith(".tflite") }
            ?.map { it.removeSuffix(".tflite") }
            ?.sorted()
            ?: emptyList()

    /** 当前选中的模型;无记录或记录失效时回退到第一个;一个都没有返回 null */
    fun getSelected(context: Context): String? {
        val models = listModels(context)
        if (models.isEmpty()) return null
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODEL, null)
        return if (saved != null && saved in models) saved else models.first()
    }

    /** 设置当前模型 */
    fun setSelected(context: Context, name: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODEL, name).commit()
    }
}
