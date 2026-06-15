"""YOLO26 焊缝缺陷检测训练脚本

用法: python train.py
首次运行会自动下载 yolo26n.pt 预训练权重。
数据集: Weld Surface Defect Detection v2 (Roboflow, 7类, 15k训练图)
"""
from pathlib import Path

from ultralytics import YOLO

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "training" / "datasets" / "Weld Surface Defect Detection.v2i.yolo26" / "data.yaml"


def main():
    model = YOLO("yolo26n.pt")  # n=最小最快,可换 yolo26s.pt 对比精度
    model.train(
        data=str(DATA),
        epochs=100,
        imgsz=640,
        batch=12,        # 4GB 显存实测 batch=8 只占 2.5G,12 提速且留余量
        patience=20,     # 20轮无提升则早停
        workers=8,       # 提高数据加载并行度,避免 GPU 等数据
        project="runs",
        name="weld_yolo26n",
        exist_ok=True,
    )
    metrics = model.val()
    print(f"mAP50: {metrics.box.map50:.4f}  mAP50-95: {metrics.box.map:.4f}")


if __name__ == "__main__":
    main()
