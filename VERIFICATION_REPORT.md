# Verification report

Version: 4.0.2-fix  
Date: 2026-09-20

## Corrected behavior

- Sustained breathing can confirm a sleep event without any confirmed snoring sequence. A single coincident snoring candidate no longer relabels the breathing event as combined.
- Breathing-only detection still applies the configured dBFS threshold, consecutive-window requirement and competing-sound false-positive filter.
- The existing recent-movement gate, automatic-pause cooldown and TV pause dispatch remain in the event path.
- Repeated breathing or snoring scores above their configured percentages can now satisfy the consecutive-window counter without a hidden absolute competing-sound ceiling.
- Startup noise calibration no longer raises the selected minimum dBFS threshold, which previously could prevent otherwise valid events from being counted.
- The configurable other-sound sensitivity remains active as a relative allowance; higher values permit a larger score gap while preserving the competing-sound check.
- A confirmed event still increments the live counter before database or TV network work starts.
- Home Assistant now receives the actual configured long-lived token in a Bearer authorization header.
- Samsung first uses secure WebSocket port 8002 and falls back to legacy port 8001; LG first uses port 3000 and falls back to secure port 3001.
- Samsung and LG retry a connection if a pause is attempted on a stale channel and recognize HTTP authorization failures during pairing recovery.

## Automated checks in this environment

- `python3 scripts/validate_source.py`: passed.
- `python3 scripts/verify_project.py`: passed.
- Kotlin delimiter and structural scan: passed for 27 Kotlin files.
- XML parse validation: passed for 4 XML files.
- Python helper syntax compilation: passed.
- Twenty-five JUnit test methods are included, including breathing autonomy, breathing false-positive filtering, movement gating, pause cooldown, corrected detection thresholds, connection fallback, and Home Assistant authorization behavior.

## Build limitation

A full Android compile, JUnit execution and APK build cannot be run in this container because Gradle and the Android SDK are not installed. No APK is claimed or included. The source project is intended for Android Studio, Gradle 8.9, or the included GitHub Actions workflow; `preBuild` provisions the metadata-enabled YAMNet model.

## Device validation still required

TV behavior varies by model, firmware and active playback application. Validate **Connect / pair** and **Test pause** on the target TV. Sleep-detection settings should also be tuned with the phone in its intended bedside position. SleepPause TV is not a medical device.
