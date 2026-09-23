# Changelog

## 4.0.2-fix — 2026-09-20

- Make final sleep-evidence confirmation independent: sustained breathing now emits a breathing event even if the trigger window contains an isolated, unconfirmed snoring score.
- Keep the competing-sound false-positive filter on breathing-only events; movement gating, pause cooldown and TV pause dispatch remain unchanged.
- Make the configured YAMNet breathing/snoring percentages and dBFS setting authoritative instead of silently raising the acoustic threshold after startup calibration.
- Replace the absolute competing-sound ceiling with a tunable relative allowance; higher sensitivity permits a larger score gap while preserving the competing-sound check.
- Restore Home Assistant authentication by sending the configured long-lived token as a Bearer credential.
- Add Samsung fallback from secure port 8002 to legacy port 8001 and reconnect once if a pause is attempted on a stale channel.
- Add LG webOS fallback from port 3000 to secure port 3001 with local self-signed-certificate support and the same reconnect behavior.
- Recognize HTTP 401/403 pairing failures so stale Samsung/LG credentials can be cleared and paired again.
- Add regressions for loud-startup calibration, competing-sound sensitivity, TV endpoint fallback, and Home Assistant authorization.

## 4.0.1-fix — 2026-09-20

- Increment the sleep-event counter immediately when detection is confirmed, without waiting for TV network I/O.
- Make monitor-state increments atomic so simultaneous callbacks cannot overwrite counts.
- Keep recording an event even when the TV is offline; update its stored pause result after a successful command.
- Reuse healthy Samsung and LG WebSocket sessions for pause commands instead of disconnecting and pairing again.
- Recover automatically from rejected stale Samsung/LG pairing keys by clearing the old key and starting one fresh pairing attempt.
- Make snoring thresholds independent from a separate breathing-score prerequisite, as documented.
- Add regression tests for independent snoring and repeated sleep events.

## 4.0.0-sleep-detection — 2026-09-20

- Reworked the detector from snoring-only events to sleep events triggered by breathing, snoring, or both.
- Added independent YAMNet score thresholds for breathing and snoring.
- Added independent consecutive-detection counts for breathing and snoring.
- Added configurable sensitivity against speech, music, TV and household sounds.
- Added configurable no-movement duration and enforced it before automatic TV pause when movement tracking is enabled.
- Preserved the automatic-pause cooldown.
- Preserved direct Samsung `KEY_PAUSE`, pairing and persistent token behavior.
- Preserved LG webOS, Roku and Home Assistant TV adapters.
- Preserved local YAMNet/TensorFlow Lite processing and the adaptive acoustic safeguards.
- Kept the existing Room schema compatible while presenting stored counts as sleep events.
- Replaced snore-only tests with sleep-event tests and added movement/pause-policy tests.
- Kept all user-facing UI and documentation in English.

## 3.0.0-universal — 2026-09-17

- Added adaptive false-positive protection and multi-brand TV support.
- Added persistent pairing credentials and local YAMNet inference.
