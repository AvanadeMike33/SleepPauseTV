#!/usr/bin/env python3
"""Download the metadata-enabled YAMNet TFLite model for an offline build."""
from pathlib import Path
from urllib.request import urlopen

URL = "https://storage.googleapis.com/download.tensorflow.org/models/tflite/task_library/audio_classification/android/lite-model_yamnet_classification_tflite_1.tflite"
ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / "app/src/main/assets/yamnet.tflite"

TARGET.parent.mkdir(parents=True, exist_ok=True)
if TARGET.exists() and TARGET.stat().st_size > 1_000_000:
    print(f"YAMNet already present: {TARGET} ({TARGET.stat().st_size} bytes)")
    raise SystemExit(0)

PART = TARGET.with_suffix(".tflite.part")
print(f"Downloading: {URL}")
with urlopen(URL, timeout=120) as response, PART.open("wb") as output:
    while chunk := response.read(1024 * 1024):
        output.write(chunk)
if PART.stat().st_size <= 1_000_000:
    PART.unlink(missing_ok=True)
    raise RuntimeError("Incomplete YAMNet download")
PART.replace(TARGET)
print(f"Installed: {TARGET} ({TARGET.stat().st_size} bytes)")
