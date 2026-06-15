# cooec 焊缝缺陷检测工程

本项目是一个面向 Android 端部署的焊缝缺陷检测系统。项目使用 YOLO 系列模型完成焊缝目标和缺陷识别,并通过 TensorFlow Lite 在手机端进行本地推理。

## 项目内容

本仓库用于做完整代码工程控制,包含以下内容:

- Android 应用源码和 Gradle 工程配置
- 训练脚本、导出脚本和数据集配置模板
- App 内置的 TensorFlow Lite 模型和标签文件
- 项目说明文档

以下内容不纳入 Git 管理:

- 本地 Python 虚拟环境
- Android 和 Gradle 构建产物
- 原始训练数据集
- 训练输出目录
- PyTorch 权重文件
- ONNX、SavedModel 等导出中间产物
- 本地批处理脚本
- 临时截图、视频和调试文件

## 当前功能

- 实时检测:调用手机相机进行实时识别并叠加检测框。
- 图片检测:从相册选择图片,识别后保存标注图和检测简表。
- 标准测试:使用内置测试视频生成检测结果、预览图和统计表。
- 权重切换:在首页选择不同检测权重,实时检测、图片检测和标准测试会加载对应模型。
- 本地保存:检测结果保存到应用外部文件目录,便于后续查看和导出。

## 目录结构

```text
WeldProject/
├── android-app/        Android 应用工程
├── training/           模型训练和导出脚本
├── README.md           项目说明
└── .gitignore          仓库忽略规则
```

## Android 工程

Android 工程位于:

```text
android-app/
```

主要代码位于:

```text
android-app/app/src/main/java/com/example/welddetect/
```

模型资产位于:

```text
android-app/app/src/main/assets/models/
```

每个模型由一组同名文件组成:

```text
模型名.tflite
模型名.txt
```

其中 `.tflite` 是手机端推理模型,`.txt` 是类别标签文件。App 会自动扫描该目录中的模型并在首页提供切换。

## 训练与导出

训练和导出脚本位于:

```text
training/
```

常用文件:

```text
training/train.py
training/train_weld_spatter_v26.py
training/train_weldspatter_gpu.py
training/export.py
training/weld.yaml
training/requirements.txt
```

导出脚本会优先读取 PyTorch 权重文件中保存的类别名称,并生成与模型匹配的标签文件,避免模型和分类标签错配。

## 构建 APK

命令行构建方式:

```powershell
cd android-app
.\gradlew.bat assembleDebug
```

构建产物:

```text
android-app/app/build/outputs/apk/debug/app-debug.apk
```

## 安装到设备

```powershell
adb install -r android-app\app\build\outputs\apk\debug\app-debug.apk
```

