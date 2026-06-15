from pathlib import Path

import torch
from ultralytics import YOLO


PROJECT_ROOT = Path(__file__).resolve().parents[1]
ROOT = PROJECT_ROOT / "weldingdataset 3.27" / "weldingdataset"
DATA = ROOT / "data_v26.yaml"
MODEL = ROOT / "yolov8n.pt"
PROJECT = ROOT / "runs" / "train"


def remove_old_caches() -> None:
    for cache in [
        ROOT / "data" / "train" / "labels.cache",
        ROOT / "data" / "valid" / "labels.cache",
    ]:
        if cache.exists():
            cache.unlink()
            print(f"Removed old cache: {cache}")


def main() -> None:
    print("==== Weld / spatter GPU training ====")
    print(f"Dataset config: {DATA}")
    print(f"Base model: {MODEL}")
    print(f"CUDA available: {torch.cuda.is_available()}")
    if torch.cuda.is_available():
        print(f"GPU: {torch.cuda.get_device_name(0)}")
    else:
        print("No CUDA GPU detected. Training will be very slow.")

    remove_old_caches()

    model = YOLO(str(MODEL))
    results = model.train(
        data=str(DATA),
        epochs=100,
        patience=20,
        imgsz=640,
        batch=8,
        device=0 if torch.cuda.is_available() else "cpu",
        workers=2,
        project=str(PROJECT),
        name="weldspatter_gpu",
        exist_ok=False,
        seed=42,
        pretrained=True,
        optimizer="auto",
        lr0=0.01,
        cos_lr=True,
        close_mosaic=10,
        val=True,
        verbose=True,
    )

    print("\n==== Training complete ====")
    print(f"Result directory: {results.save_dir}")
    print(f"Best weight: {Path(results.save_dir) / 'weights' / 'best.pt'}")


if __name__ == "__main__":
    main()
