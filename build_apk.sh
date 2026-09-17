#!/usr/bin/env sh
set -eu

if command -v ./gradlew >/dev/null 2>&1; then
  GRADLE=./gradlew
elif command -v gradle >/dev/null 2>&1; then
  GRADLE=gradle
else
  echo "Gradle was not found. Open the project in Android Studio or install Gradle 8.9." >&2
  exit 1
fi

python3 scripts/download_yamnet.py
$GRADLE test assembleDebug
printf '\nAPK: app/build/outputs/apk/debug/app-debug.apk\n'
