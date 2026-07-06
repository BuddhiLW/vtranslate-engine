# Local Models

This directory is for machine-local ASR/VAD artifacts. Keep the model binaries out of git; `.gitignore` only allows this README and optional `.gitkeep` to be tracked.

Expected filenames used by the current engine smoke runs:

- `ggml-large-v3.bin` — whisper.cpp large-v3 model for the `:whisper-jni` adapter.
- `silero_vad.onnx` — Silero VAD ONNX model for the `:silero-vad` segmenter.

Example live media run from `vtranslate-engine`:

```bash
clojure -M:ffmpeg:whisper-jni:silero-vad:run '{:job-id "j1" :source "../corpus/multisource/multisource.mp4" :source-language "auto" :target-language "pt-BR" :format :format/srt :config {:segmenter :silero-vad :transcriber :whisper-local :translator :venice :segmenter-opts {:model-path "models/silero_vad.onnx"} :transcriber-opts {:model-path "models/ggml-large-v3.bin"}}}'
```
