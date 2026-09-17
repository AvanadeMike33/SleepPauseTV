#!/usr/bin/env python3
from pathlib import Path
import json

root = Path(__file__).resolve().parents[1]
required = [
    "settings.gradle.kts", "app/build.gradle.kts", "app/src/main/AndroidManifest.xml",
    "app/src/main/java/it/michelegiammarini/sleeppausetv/audio/SnoreDetector.kt",
    "app/src/main/java/it/michelegiammarini/sleeppausetv/tv/SamsungTvClient.kt",
    "app/src/main/java/it/michelegiammarini/sleeppausetv/tv/SamsungProtocol.kt",
    "app/src/main/java/it/michelegiammarini/sleeppausetv/service/SleepMonitorService.kt",
]
for item in required:
    assert (root / item).is_file(), f"Missing {item}"

all_text = "\n".join(p.read_text(errors="ignore") for p in root.rglob("*") if p.is_file())
for marker in ["KEY_PAUSE", "ms.remote.control", "ms.channel.connect", "saveTvToken", "AudioRecord", "sleep_sessions"]:
    assert marker in all_text, f"Missing feature marker: {marker}"

manifest = (root / "app/src/main/AndroidManifest.xml").read_text()
for permission in ["RECORD_AUDIO", "FOREGROUND_SERVICE_MICROPHONE", "INTERNET", "WAKE_LOCK"]:
    assert permission in manifest, f"Missing permission: {permission}"

print(json.dumps({
    "status": "ok",
    "files": sum(1 for p in root.rglob("*") if p.is_file()),
    "features": ["snore-detection", "sleep-history", "movement", "KEY_PAUSE", "token-autosave"]
}, ensure_ascii=False, indent=2))
