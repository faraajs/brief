# Changelog

## 1.0.0

Initial public release of Brief (`id.eska.brief`).

- Offline video/audio to Opus (`.ogg`) and AAC (`.m4a`) conversion on Android 10+ (`arm64-v8a`), with destination size and duration auto-splitting for NotebookLM, Gemini Chat, ChatGPT, and Grok.
- Full-stream Silero VAD v6.2 pause shortening (trims pauses of 2 seconds or longer down to 0.6 seconds while preserving quiet speech).
- Speaker voice clarity pass (85 Hz Butterworth high-pass filter and peak-guarded speech-aware leveler).
- Optional GTCRN neural noise reduction (70/30 wet/dry blend, 4-worker parallel chunking with 1.024 second RNN warmup, runs after pause trimming).
- Pipelined async encoding, fast integer resampling for 16/32/48 kHz sources, and optional battery-optimization exemption for full-speed background conversion.
- Bilingual English (default) and Indonesian UI with system-following and manual in-app language and Light/Dark/Auto theme controls.
