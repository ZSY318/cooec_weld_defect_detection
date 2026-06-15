"""把训练好的权重导出为安卓可用的 TFLite 格式,并按"模型名"规范化命名。

用法:
    python export.py                          # 用下面的默认值
    python export.py <权重路径> <模型名>       # 自定义

产物会成对复制到 android-app 的 assets/models/ 目录:
    <模型名>.tflite   推理模型
    <模型名>.txt      类别标签(每行一个,顺序与训练一致)

App 启动时自动扫描该目录,支持多模型切换。以后训练新权重只需换个模型名再导出即可。
"""
import shutil
import sys
from pathlib import Path

import yaml
from ultralytics import YOLO

# ===== 默认配置(可被命令行参数覆盖)=====
ROOT = Path(__file__).resolve().parents[1]
WEIGHTS = ROOT / "training" / "runs" / "detect" / "runs" / "weld_yolo26n" / "weights" / "best.pt"
DATA = ROOT / "training" / "datasets" / "Weld Surface Defect Detection.v2i.yolo26" / "data.yaml"
MODEL_NAME = "20260613_YOLOv26_Default"
# =========================================

if len(sys.argv) > 1:
    WEIGHTS = Path(sys.argv[1])
if len(sys.argv) > 2:
    MODEL_NAME = sys.argv[2]

print(f"权重: {WEIGHTS}")
print(f"模型名: {MODEL_NAME}")

model = YOLO(str(WEIGHTS))
# fp16 精度优先;验证手机端效果后如需更小体积再尝试 half=False, int8=True
out = model.export(format="tflite", half=True, imgsz=640)
print(f"导出原始文件: {out}")

assets = ROOT / "android-app" / "app" / "src" / "main" / "assets" / "models"
assets.mkdir(parents=True, exist_ok=True)

# 1) 模型文件
dst_model = assets / f"{MODEL_NAME}.tflite"
shutil.copy(out, dst_model)

# 2) 标签文件。优先使用权重文件里保存的类别名,避免换权重后仍写入旧 data.yaml 标签。
names = model.names
if not names:
    names = yaml.safe_load(open(DATA, encoding="utf-8"))["names"]
if isinstance(names, dict):  # {0: 'a', 1: 'b'} 形式
    names = [names[i] for i in sorted(names)]
(assets / f"{MODEL_NAME}.txt").write_text("\n".join(names), encoding="utf-8")

print(f"\n已导出到: {dst_model}")
print(f"标签 ({len(names)} 类): {assets / (MODEL_NAME + '.txt')}")
print("重新编译 App 即可使用该模型。")
