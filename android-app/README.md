# 焊缝缺陷检测 Android Demo

完整可编译的 Android Studio 工程:CameraX 实时取流 → TFLite 推理 → 检测框叠加显示。

## 运行步骤

1. **放模型**:运行 `../training/export.py` 后,把生成的 `.tflite` 从 `models/`
   复制到 `app/src/main/assets/`(assets 中放且只放一个 .tflite,代码会自动找到它)
2. **改标签**:编辑 `app/src/main/assets/labels.txt`,每行一个类别,
   顺序必须与 `training/weld.yaml` 完全一致
3. 用 Android Studio 打开本目录(android-app),等 Gradle 同步完成
4. 连接手机(开启 USB 调试),点 Run

## 代码结构

| 文件 | 作用 |
|------|------|
| `MainActivity.kt` | 相机权限、CameraX 取流(RGBA 直出)、调度推理 |
| `Detector.kt` | TFLite 加载与推理;letterbox 预处理;自动适配 YOLO26 端到端输出([1,N,6],免 NMS)和 v8 经典输出([1,4+nc,8400],内置 NMS) |
| `OverlayView.kt` | 在预览画面上绘制检测框、类别和置信度 |

底部状态栏实时显示检出数量和单帧耗时。

## 调参位置

- 置信度/IoU 阈值:`Detector` 构造参数(默认 0.25 / 0.45)
- 推理线程数:`Detector.kt` 中 `numThreads = 4`
- 如需 GPU 加速:在 `app/build.gradle.kts` 加 `tensorflow-lite-gpu` 依赖,
  并在 Interpreter.Options 中 addDelegate(GpuDelegate())

## 注意事项

- 模型输入尺寸自动从模型读取(训练/导出用 640 即可)
- 先用 fp16 模型验证精度,确认无误后再考虑 int8 量化
- `noCompress "tflite"` 已配置,勿删,否则模型无法内存映射加载
