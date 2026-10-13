#!/usr/bin/env python3
"""
Export the YOLOv8s Open Images V7 model (601 classes, includes "Waste container")
to ONNX for use by the Android app.

Usage:
    pip install ultralytics onnx onnxruntime
    python tools/export_model.py

Output:
    app/src/main/assets/yolov8s-oiv7.onnx

The class list expected by the app lives in app/src/main/assets/labels.txt and
must stay in the same order as the model's class indices (the order below comes
from the ultralytics open-images-v7 dataset definition).
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT_DIR = ROOT / "app" / "src" / "main" / "assets"


def main() -> None:
    from ultralytics import YOLO

    OUT_DIR.mkdir(parents=True, exist_ok=True)

    # Downloads yolov8s-oiv7.pt (~22 MB) from the ultralytics assets release.
    model = YOLO("yolov8s-oiv7.pt")

    exported = model.export(format="onnx", imgsz=640, opset=17, simplify=False)
    exported_path = Path(exported)
    target = OUT_DIR / "yolov8n-oiv7.onnx"
    target.write_bytes(exported_path.read_bytes())
    print(f"ONNX model written to {target}")

    names = model.names
    if isinstance(names, dict):
        names = [names[i] for i in sorted(names)]
    labels_path = OUT_DIR / "labels.txt"
    labels_path.write_text("\n".join(str(n) for n in names), encoding="utf-8")
    print(f"{len(names)} class names written to {labels_path}")


if __name__ == "__main__":
    main()
