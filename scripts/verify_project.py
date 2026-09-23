#!/usr/bin/env python3
from pathlib import Path
import argparse
import json

parser = argparse.ArgumentParser()
parser.add_argument("--require-model", action="store_true")
args = parser.parse_args()

root = Path(__file__).resolve().parents[1]
required = [
    "settings.gradle.kts",
    "app/build.gradle.kts",
    "app/src/main/AndroidManifest.xml",
    "app/src/main/java/it/michelegiammarini/sleeppausetv/audio/YamnetAudioClassifier.kt",
    "app/src/main/java/it/michelegiammarini/sleeppausetv/data/SettingsStore.kt",
    "app/src/main/java/it/michelegiammarini/sleeppausetv/service/PausePolicy.kt",
    "app/src/main/java/it/michelegiammarini/sleeppausetv/service/SleepMonitorService.kt",
    "app/src/main/java/it/michelegiammarini/sleeppausetv/tv/TvController.kt",
    "app/src/main/java/it/michelegiammarini/sleeppausetv/tv/SamsungTvClient.kt",
    "app/src/main/java/it/michelegiammarini/sleeppausetv/tv/LgWebOsTvClient.kt",
    "app/src/main/java/it/michelegiammarini/sleeppausetv/tv/RokuTvClient.kt",
    "app/src/main/java/it/michelegiammarini/sleeppausetv/tv/HomeAssistantTvClient.kt",
    "scripts/download_yamnet.py",
]
for item in required:
    assert (root / item).is_file(), f"Missing {item}"

text_files = [p for p in root.rglob("*") if p.is_file() and p.suffix != ".tflite"]
all_text = "\n".join(p.read_text(errors="ignore") for p in text_files)
for marker in [
    "tensorflow-lite-task-audio",
    "AudioClassifier.createFromFile",
    "SleepDecisionEngine",
    "snoringConfidence",
    "snoringConsecutiveDetections",
    "breathingConfidence",
    "breathingConsecutiveDetections",
    "otherSoundSensitivity",
    "noMovementMinutes",
    "PausePolicy.evaluate",
    "KEY_PAUSE",
    "ssap://media.controls/pause",
    "keypress/Play",
    "media_player/media_pause",
    "saveTvToken",
    "sleep_sessions",
]:
    assert marker in all_text, f"Missing feature marker: {marker}"

manifest = (root / "app/src/main/AndroidManifest.xml").read_text()
for permission in ["RECORD_AUDIO", "FOREGROUND_SERVICE_MICROPHONE", "INTERNET", "WAKE_LOCK"]:
    assert permission in manifest, f"Missing permission: {permission}"

model = root / "app/src/main/assets/yamnet.tflite"
if args.require_model:
    assert model.is_file() and model.stat().st_size > 1_000_000, "YAMNet model missing or incomplete"

print(json.dumps({
    "status": "ok",
    "files": sum(1 for p in root.rglob("*") if p.is_file()),
    "modelBundled": model.is_file() and model.stat().st_size > 1_000_000,
    "version": "4.0.2-fix",
    "features": [
        "local-yamnet-tflite",
        "independent-breathing-and-snoring-thresholds",
        "independent-consecutive-detection-counts",
        "autonomous-breathing-event-with-confirmed-evidence",
        "combined-sleep-event",
        "configurable-other-sound-sensitivity",
        "configurable-no-movement-gate",
        "configured-db-threshold-without-hidden-adaptive-override",
        "event-latch-and-refractory-period",
        "persistent-tv-pairing",
        "samsung-lg-roku-home-assistant",
    ],
}, indent=2))
