package com.example.welddetect

object ModelDisplay {
    fun name(modelName: String): String = when {
        modelName.contains("RealTime_Detection_Weight", ignoreCase = true) -> "实时检测权重"
        modelName.contains("WeldSpatter", ignoreCase = true) -> "焊缝飞溅默认权重"
        modelName.contains("YOLOv26", ignoreCase = true) -> "焊缝缺陷默认权重"
        modelName.contains("Default", ignoreCase = true) -> "默认权重"
        else -> modelName
    }
}
