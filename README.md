# Brief

Convert long videos and voice recordings on Android into small, pause-trimmed audio files ready for AI transcription.

[![Latest release](https://img.shields.io/github/v/release/faraajs/brief?label=Latest%20release)](https://github.com/faraajs/brief/releases/latest)
![License: MIT](https://img.shields.io/badge/license-MIT-blue)
![Android 10+](https://img.shields.io/badge/Android-10%2B-green)
![ABI: arm64-v8a](https://img.shields.io/badge/ABI-arm64--v8a-blue)

## Download

**[Download Brief 1.0.1 APK (7.7 MB, arm64-v8a)](https://github.com/faraajs/brief/releases/download/v1.0.1/Brief-v1.0.1-arm64-v8a.apk)**

Requires Android 10 or newer and a 64-bit `arm64-v8a` device. Download the `.apk` file from the release assets, not GitHub's source code archives.

[Release page and checksums](https://github.com/faraajs/brief/releases/tag/v1.0.1) · [All releases](https://github.com/faraajs/brief/releases)

### Verify before installing

Check the downloaded APK against `SHA256SUMS.txt` from the release page:

```sh
sha256sum -c SHA256SUMS.txt
```

Expected SHA-256 for `Brief-v1.0.1-arm64-v8a.apk`:

```text
74b24f48e83b0be112f92f9b49b8df7ce19ae889b5433d90ed3a8a766c65887b
```

With Android SDK Build Tools installed, you can also check the signing certificate:

```sh
apksigner verify --verbose --print-certs Brief-v1.0.1-arm64-v8a.apk
```

Official release certificate (`CN=Brief Release, O=Brief`, RSA 3072-bit, APK Signature Scheme v3) SHA-256:

```text
0c4ef8f82ab62d5d3e6fc42417e97fc980973acc8cc4bc5d32df7d525fabd828
```

## What Brief does

Brief runs fully offline on your phone, leaves original recordings untouched, and writes new files to `Downloads/Brief/`.

- Accepts any video or audio file via Android's document picker or the system Share menu.
- Encodes mono 16 kHz speech to Ogg Opus (`12`, `16`, `24` default, or `32` kbps) or M4A AAC-LC (`32` or `48` kbps). At the default `24` kbps Opus setting, one hour of speech takes about 10.8 MB.
- Auto-splits long recordings to fit upload limits for NotebookLM (single file up to 195 MB), Gemini Chat (9m 50s parts, 98 MB cap), ChatGPT (5m 50s parts, 98 MB cap), and Grok (single file or 10 minute parts, 145 MB cap), adding a 1 second overlap when a split lands during speech.
- Shortens long pauses (2 seconds or longer trimmed to 0.6 seconds) using full-stream Silero VAD v6.2 (`silero_vad_16k.onnx`) with adaptive peak-safe gain up to 32x, so steady fan or AC noise still trims while soft voices stay intact.
- Clarifies speakers with an 85 Hz Butterworth high-pass filter and a peak-guarded, speech-aware leveler that lifts quiet voices without boosting silence or clipping loud peaks.
- Offers optional neural noise reduction via GTCRN (`gtcrn_simple.onnx`, 524 KB) blended 70% enhanced / 30% original so consonants and distant speech survive. GTCRN runs after pause trimming across up to 4 parallel chunk workers (`19.2 s` chunks with `1.024 s` RNN warmup). It starts off by default because clean recordings transcribe with fewer errors using the lighter clarity pass alone.
- Supports English (default) and Indonesian, plus Light and Dark themes, either following system settings or set manually from the top bar.

## Audio pipeline

```text
Input media -> MediaExtractor + MediaCodec -> 16 kHz mono resampler
  -> 85 Hz high-pass filter
  -> Silero VAD v6.2 pause trimmer (optional, default on)
  -> GTCRN 70/30 denoiser (optional, default off, parallel chunks)
  -> Speech-aware peak-guarded leveler (optional, default on)
  -> Async encoder thread -> Ogg Opus or M4A AAC in Downloads/Brief/
```

## Measured performance

Numbers below come from tests on one Android 13 Snapdragon 855 `arm64-v8a` phone and a 250-word Indonesian speech benchmark checked with `whisper-large-v3-turbo` at 16 kbps Opus. Speed and accuracy vary by device and recording.

| Check | Result |
| --- | --- |
| 22-minute recording, pause trim + voice clarity | Finished in 19.1 s (69.3x real-time); cut 3m 46s (17.1%) of fan-noise pauses |
| 22-minute recording, pause trim + voice clarity + GTCRN | Finished in 61.7 s (21.4x real-time, about 2.8 min per hour of audio) |
| 60 s resample (48 kHz stereo to 16 kHz mono) | 135 ms via integer fast path |
| Clean speech WER (250-word test, 16 kbps Opus) | 8.80% raw vs 6.40% with voice clarity (22 vs 16 word errors) |
| Distant quiet speech (-26 dB) + heavy rumble WER | 96.40% raw vs 92.40% with 70/30 GTCRN (100% error at 100% wet) |
| Whisper clip check (20 s FLEURS clip, peak 44 / 32768) | Preserved 11.60 s of speech in both default and denoise modes |

## Permissions

Brief declares no `INTERNET` permission and no broad shared-storage permission. Processing stays on the device, and `android:allowBackup` is set to `false`.

| Permission | Reason |
| --- | --- |
| `FOREGROUND_SERVICE` | Keeps long conversions running with a progress notification after you leave the app. |
| `WAKE_LOCK` | Holds a partial wake lock while a conversion is active, released immediately on completion or cancel. |
| `POST_NOTIFICATIONS` | Shows conversion progress notifications on Android 13+. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Optional one-time prompt so the OS does not throttle background conversions to low-power cores. Idle usage stays zero when no job is running. |

## Build from source

Requirements: JDK 17 and Android SDK with `compileSdk 36` and Build Tools (`apksigner`).

```sh
./gradlew :app:assembleRelease
```

To run the host DSP and VAD logic tests (`tests/SignalTest.java`):

```sh
./tools/test.sh
```

To build, sign with a local keystore stored outside the checkout, and print checksums:

```sh
./tools/release.sh
```

By default `tools/release.sh` creates or reuses a keystore under `${XDG_DATA_HOME:-$HOME/.local/share}/brief/signing/`. You can point it to your own keystore with `BRIEF_KEYSTORE` and `BRIEF_STORE_PASSWORD_FILE`. Private signing keys are never stored in this repository.

## Verification limits

- Host tests (`./tools/test.sh`) verify pause trimming, fan-noise trimming, quiet-speech preservation, the 85 Hz high-pass filter, leveler peak clamping, 512-point FFT roundtrip accuracy, and multi-rate resampling.
- Signed APK installation and on-device pipeline runs were verified on one physical Android 13 `arm64-v8a` phone.
- Background execution speed still depends on OEM battery policies on other Android brands.

## Licenses

Brief is licensed under the [MIT License](LICENSE). Bundled ONNX models (Silero VAD v6.2 and GTCRN) and ONNX Runtime Android are also MIT licensed; see [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).
