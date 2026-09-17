# Changelog

## 3.0.0-universal — 2026-09-17

- Rebuilt the entire interface and documentation in English.
- Raised the default YAMNet snoring threshold from 30% to 45%.
- Added an adaptive noise floor and 6 dB SNR gate.
- Added score-dominance checks and broader competing-sound rejection.
- Added a refractory period to avoid duplicate counts from fragmented audio.
- Fixed session startup so counters and aggregates no longer carry over from a prior session.
- Added direct control for Samsung, LG webOS and Roku.
- Added Home Assistant `media_pause` as a cross-brand adapter.
- Added unit tests for false-positive scenarios and LG protocol payloads.
- Updated the app version to `3.0.0-universal`.
