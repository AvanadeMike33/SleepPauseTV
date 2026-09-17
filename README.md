# SleepPause TV Universal 3.0

SleepPause TV is an Android companion app that listens locally for sustained snoring and sends a pause command to a television. Version 3.0 reduces false snore counts, resets every new session correctly, uses English throughout, and adds multiple TV-control adapters.

## What changed

### False-positive protection

The original build accepted two YAMNet windows when `Snoring` passed a low fixed score and `Speech`/`Music` stayed below a fixed cutoff. Version 3.0 adds:

- a higher balanced default YAMNet threshold (**45%**, still adjustable);
- a six-window startup calibration, adaptive room-noise estimate and **6 dB signal-to-noise gate**;
- snoring-score dominance over competing audio, not only a fixed blocker;
- respiratory context, with a stricter exception only for very strong snoring;
- rejection of TV/radio, voice, music, vehicles, motors, fans, appliances, alarms, pets, coughs, sneezes and abrupt household sounds;
- multi-window confirmation and a two-window release rule;
- a four-window refractory period so one fragmented sound is not counted twice;
- full reset of counts and acoustic aggregates at the start of every session.

These rules intentionally favor fewer false alarms over detecting every faint snore. Keep the phone close to the sleeper, away from TV speakers, and begin with the default settings.

### TV support

| Platform | Connection | Pause action | Notes |
|---|---|---|---|
| Samsung Smart TV | Secure LAN WebSocket, port 8002 | `KEY_PAUSE` | Approve first pairing on the TV; token is stored locally. |
| LG webOS TV | LAN WebSocket, port 3000 | `ssap://media.controls/pause` | Approve first pairing on the TV; client key is stored locally. |
| Roku TV / Roku player | ECP HTTP, port 8060 | Playback-state check, then `keypress/Play` | The app does not send the toggle unless Roku reports active playback; enable mobile-app control when required. |
| Home Assistant / other brands | Home Assistant REST API | `media_player.media_pause` | Covers Android/Google TV, Fire TV, Sony, Philips, TCL, Hisense, Panasonic and other brands when a compatible Home Assistant `media_player` integration is configured. |

No single vendor-neutral protocol can pause every source on every television. A TV can ignore a command when the current HDMI source or streaming app does not expose transport control. The Home Assistant option is the portable fallback for brands without a direct adapter.

## Privacy and safety

- YAMNet inference is performed on the Android device.
- Raw audio is never saved or transmitted.
- Session history stores counts, durations, sound-level summaries and whether a pause succeeded.
- Home Assistant credentials and TV pairing keys are stored in the app's private DataStore.
- LG and Roku use their vendors' unauthenticated or paired local-network endpoints, so use SleepPause TV only on a trusted LAN.
- SleepPause TV is not a medical device and does not diagnose sleep apnea or any health condition.

## Requirements

- Android 8.0 / API 26 or later;
- Java 17 and Android Studio with Android SDK 35 for source builds;
- microphone permission and, on Android 13+, notification permission;
- Internet access during the first build unless the YAMNet model is downloaded in advance;
- phone and TV on the same network for direct Samsung, LG or Roku control.

## Build and install

1. Open this folder in Android Studio.
2. Sync Gradle. The `downloadYamnetModel` task downloads the metadata-enabled TFLite model before `preBuild`.
3. Build the debug APK with Android Studio (**Build > Build APK(s)**) or Gradle 8.9 (`gradle assembleDebug`).
4. Install `app/build/outputs/apk/debug/app-debug.apk` on the Android phone.

The source archive intentionally omits the binary Gradle wrapper. Android Studio can use its bundled Gradle installation, or you can generate the wrapper once with `gradle wrapper --gradle-version 8.9`. A GitHub Actions workflow is included at `.github/workflows/build-apk.yml`; pushing the project to GitHub and running **Build Android APK** produces a downloadable debug APK artifact.
5. Grant microphone and notification permissions.

For an offline build, download the model first:

```bash
python3 scripts/download_yamnet.py
gradle assembleDebug --offline
```

## Configure a TV

### Samsung

1. Select **Samsung**.
2. Enter the TV's local IP address.
3. Tap **Connect / pair** and approve the prompt on the TV.
4. Tap **Test pause**.

### LG webOS

1. Select **LG webOS**.
2. Enter the TV's local IP address.
3. Tap **Connect / pair** and approve the prompt on the TV.
4. Tap **Test pause**.

### Roku

1. Select **Roku TV**.
2. Enter the Roku device's local IP address.
3. If connection fails, enable control by mobile apps in the Roku network/control settings.
4. Tap **Test pause**. The app checks Roku's media-player state first and sends the Play/Pause toggle only while playback is active.

### Home Assistant / other brands

1. Add the TV to Home Assistant and confirm it appears as a `media_player` entity.
2. Create a Home Assistant long-lived access token.
3. Select **Home Assistant / other brands**.
4. Enter the base URL, token and entity ID, for example `media_player.living_room_tv`.
5. Tap **Connect / pair**, then **Test pause**.

Prefer an HTTPS Home Assistant URL. HTTP is supported for trusted local installations because some TV LAN APIs also require cleartext traffic.

## Detection tuning

Start with:

- minimum sound level: **-42 dBFS**;
- minimum YAMNet snoring score: **45%**;
- minimum time between automatic pauses: **10 minutes**.

If real snores are missed, move the phone closer before lowering the model threshold. If false positives remain, raise the YAMNet threshold to 55–65% or raise the sound threshold. TV dialogue and music should be rejected automatically, but microphone placement remains important.

## Verification

```bash
python3 scripts/verify_project.py
python3 scripts/download_yamnet.py
python3 scripts/verify_project.py --require-model
gradle test
gradle assembleDebug
```

Unit tests cover persistence, respiratory context, competing-audio rejection, adaptive noise gating, duplicate-count suppression, and Samsung/LG protocol payloads.

## Technical notes

- Audio input: mono 16 kHz through TensorFlow Lite Task Audio.
- Model: YAMNet AudioSet classifier.
- Minimum Android version: API 26.
- Target/compile SDK: API 35.
- Version: `3.0.0-universal`.

References:

- TensorFlow YAMNet: https://github.com/tensorflow/models/tree/master/research/audioset/yamnet
- Samsung Smart TV Web APIs: https://developer.samsung.com/smarttv/develop/api-references/web-api-references.html
- LG webOS TV developer portal: https://webostv.developer.lge.com/
- Roku External Control Protocol: https://developer.roku.com/docs/developer-program/dev-tools/external-control-api.md
- Home Assistant REST API: https://developers.home-assistant.io/docs/api/rest/
