package com.example.welddetect

object LabelDisplay {
    private val names = mapOf(
        "weld" to "焊缝",
        "spatter" to "飞溅",
        "Burn Through-BT" to "烧穿",
        "Contamination-CN" to "污染",
        "Lack of Fusion-LOF" to "未熔合",
        "Normal Weld-NW" to "正常焊缝",
        "Spatter-SR" to "飞溅",
        "Surface Crack-SC" to "表面裂纹",
        "Undercut-UC" to "咬边",
    )

    fun name(raw: String): String = names[raw] ?: raw
}
