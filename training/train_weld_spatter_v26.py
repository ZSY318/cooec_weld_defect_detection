"""用 YOLO26 (yolo26n) 在 weld/spatter 数据集上重新训练。

数据集: ../weldingdataset 3.27/weldingdataset/data  (606 train / 51 val)
说明: 标签为多边形分割格式, ultralytics 在 detect 任务下会自动转为检测框。
      当前数据集中只含 weld(类别0) 标注, spatter(类别1) 无样本。
输出: training/runs/detect/runs/weld_spatter_yolo26n
"""
from pathlib import Path

from ultralytics import YOLO

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "weldingdataset 3.27" / "weldingdataset" / "data_v26.yaml"
MODEL = ROOT / "training" / "yolo26n.pt"
PROJECT = ROOT / "training" / "runs" / "detect" / "runs"
NAME = "weld_spatter_yolo26n"


def main():
    model = YOLO(str(MODEL))
    model.train(
        data=str(DATA),
        epochs=100,
        imgsz=640,
        batch=8,          # RTX 3050 4GB 安全值
        patience=20,      # 20轮无提升早停
        workers=8,
        device=0,
        seed=42,
        project=str(PROJECT),
        name=NAME,
        exist_ok=True,
    )
    metrics = model.val()
    print(f"mAP50: {metrics.box.map50:.4f}  mAP50-95: {metrics.box.map:.4f}")


if __name__ == "__main__":
    main()
