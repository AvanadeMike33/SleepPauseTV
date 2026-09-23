# SleepPause TV 4.0.2

SleepPause TV is an Android companion app that pauses a TV when on-device YAMNet analysis detects sustained sleep-related breathing, sustained snoring, or both together. If movement tracking is enabled, an automatic pause is sent only after the configured no-movement period has elapsed.

The app and all user-facing text are in English.

## Core behavior

1. The microphone is analyzed locally in approximately 0.975-second YAMNet windows.
2. Breathing and snoring are evaluated independently, each with its own minimum YAMNet score and required number of consecutive detections.
3. Either confirmed signal can create one sleep event. A breathing sequence therefore works without snoring. The event is reported as **Breathing and snoring** only when both independent consecutive-detection requirements have been completed; an isolated snoring score cannot relabel or block a breathing event.
4. Speech, music, TV/radio sound and common household noises are treated as competing sounds. The **Sensitivity versus other sounds** control adjusts how much separation is required.
5. A short startup calibration prevents transient detections, but it no longer raises the configured dBFS threshold in the background.
6. A latch, release rule and refractory period prevent one sustained sound from creating repeated events.
7. If movement tracking is enabled, the event can pause the TV only when no device movement has been detected for the configured number of minutes.
8. The existing automatic-pause cooldown prevents repeated pause commands.
9. A confirmed sleep event is counted immediately; TV connection delays or failures cannot block the counter.

Example: set **Minimum YAMNet breathing score** to **50%** and **Consecutive breathing detections** to **4**. Four qualifying breathing windows in a row create one sleep event. A pause is then attempted only if automatic pause is enabled, the movement condition is satisfied, and the cooldown permits it.

## Preserved TV and ML components

The verified implementation was retained rather than duplicated:

| Platform | Connection | Pause action | Persistent credential |
|---|---|---|---|
| Samsung Smart TV | Secure LAN WebSocket on port 8002, with legacy port 8001 fallback | `KEY_PAUSE` | Samsung token in private DataStore |
| LG webOS TV | LAN WebSocket on port 3000, with secure port 3001 fallback | `ssap://media.controls/pause` | LG client key in private DataStore |
| Roku TV / Roku player | ECP HTTP, port 8060 | Playback-state check, then `keypress/Play` | None required |
| Home Assistant / other brands | Home Assistant REST API | `media_player.media_pause` | Long-lived token in private DataStore |

YAMNet inference still uses TensorFlow Lite Task Audio with mono 16 kHz microphone input. Raw audio is never stored or transmitted.

## Detection settings

- **Minimum YAMNet snoring score:** 10–95%; default 45%.
- **Consecutive snoring detections:** 1–12; default 2.
- **Minimum YAMNet breathing score:** 10–95%; default 50%.
- **Consecutive breathing detections:** 1–12; default 4.
- **Minimum sound level:** -60 to -25 dBFS; default -42 dBFS.
- **Sensitivity versus other sounds:** 0–100%; default 50%. Higher values are more permissive when sleep cues overlap competing sounds.
- **Track device movement:** on by default.
- **Required time without movement:** 1–60 minutes; default 5 minutes.
- **Minimum time between automatic pauses:** 1–60 minutes; default 10 minutes.

Settings are persisted in Android DataStore. Existing installations also migrate the previous single snoring-threshold preference into the new snoring threshold.

## TV setup

### Samsung

1. Select **Samsung**.
2. Enter the local TV IP address or hostname.
3. Tap **Connect / pair** and approve the prompt on the TV.
4. Tap **Test pause**.

The pairing token is stored locally and reused. **Forget pairing key** removes it.

### LG webOS

1. Select **LG webOS**.
2. Enter the local TV IP address or hostname.
3. Tap **Connect / pair** and approve the prompt on the TV.
4. Tap **Test pause**.

The client key is stored locally and reused.

### Roku

1. Select **Roku TV**.
2. Enter the local Roku IP address.
3. Enable control by mobile apps in Roku settings if required.
4. Tap **Test pause**.

The app checks playback state before sending Roku's Play/Pause toggle.

### Home Assistant / other brands

1. Add the TV to Home Assistant as a working `media_player` entity.
2. Create a Home Assistant long-lived access token.
3. Select **Home Assistant / other brands**.
4. Enter the base URL, token and entity ID.
5. Tap **Connect / pair**, then **Test pause**.

Prefer HTTPS. Cleartext HTTP remains available for trusted local installations because some TV LAN APIs require it.

## Requirements

- Android 8.0 / API 26 or later.
- Java 17.
- Android SDK 35.
- Gradle 8.9.
- Microphone permission and, on Android 13 or later, notification permission.
- Phone and TV on the same network for direct Samsung, LG or Roku control.
- Internet access for the first source build unless `yamnet.tflite` is already present.

## Build

Open the project in Android Studio and build the debug APK, or run:

```bash
python3 scripts/download_yamnet.py
gradle testDebugUnitTest assembleDebug
```

The APK is created at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

The `downloadYamnetModel` Gradle task runs before `preBuild`, so the metadata-enabled model is provisioned automatically when network access is available. The included GitHub Actions workflow also runs the tests and produces a downloadable debug APK artifact.

## Verification

```bash
python3 scripts/validate_source.py
python3 scripts/verify_project.py --require-model
gradle testDebugUnitTest assembleDebug
```

Unit tests cover independent breathing/snoring thresholds, consecutive-window behavior, combined events, competing-sound sensitivity, configured dB-threshold behavior after loud startup calibration, sustained-event latching, movement gating, pause cooldown, TV endpoint fallback, Home Assistant authorization, and the retained TV protocol payloads.

## Privacy and limitations

- Audio classification is local.
- Raw audio is not saved or uploaded.
- Session history stores counts, duration, sound-level summaries, movement counts and pause outcomes.
- Pairing keys and tokens are stored in app-private DataStore.
- Device movement means movement of the phone, not direct body tracking.
- TV firmware and playback apps can differ; always validate **Connect / pair** and **Test pause** on the target setup.
- SleepPause TV is not a medical device and does not diagnose sleep disorders.
