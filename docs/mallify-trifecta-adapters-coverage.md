# Adapter contract / trifecta coverage

Branch: `swarm/mallify-trifecta-adapters`. Scope: every namespace under
`src/vtranslate/engine/adapters/**` (48 namespaces). Ports, providers, collect,
pipeline and top-level facades are in `docs/mallify-trifecta-providers-coverage.md`.

## Status

**Complete for the adapters scope.** Every public var is either referenced by a
unit test or excluded below with a concrete native/process reason. Nothing is
waived. No runtime source and no `schemas/vtranslate/engine/schema.clj` changed.
The new schemas are under `schemas/` only (`port_values.clj`,
`adapters_boundaries.clj`, `adapters_gates.clj`), and `src` stays malli-free.

## Method

The inventory comes from clj-kondo static analysis (var definitions in
`src/vtranslate/engine/adapters`, and var usages from `test/`), cross-checked
with carto `read-file`/`ns` reads of each namespace. A var counts as
*referenced* when some file under `test/` resolves a usage to it by qualified
name. Calls that go through `with-redefs`, registry `defmethod` dispatch
(`resolve-transcriber`, `resolve-segmenter`, ...) or record protocol methods
are counted by what the test names explicitly. Record constructors
(`->X`/`map->X`) are listed separately, because a record is contracted through
its port protocol rather than through its constructor var.

- **T** — `deftrifecta` with a `:golden-path test/golden/adapters-*.edn`, named
  `:cases`, a bounded test.check `:gen`, and a `:pred` that validates a malli
  schema and checks a relational property over a test-local `{:in :out}`
  observation.
- **C** — offline contract/unit test. Transport, WAV reads and native entry
  points are stubbed, and the port value schema (`schema.port-values` /
  `schema.adapters-gates`) is validated. No network, native library, ffmpeg
  process or interpreter is involved.
- **E** — excluded. The var cannot load or run on the `:test` classpath without
  a native/JNI/FFM/ONNX/Python/bytedeco alias. The integration suites that do
  exercise these vars (`test-int/`, `test-onnx/`) are named in the table.

## Totals

| | count |
|---|---|
| adapter namespaces | 48 |
| public vars (all) | 253 |
| public fn/def vars (excluding record ctors) | 154 |
| referenced by a unit test | 129 |
| excluded (E) | 25 (all in 8 native/bytedeco namespaces + 2 ffmpeg composers' `make-composer`) |
| unexplained gaps | 0 |
| adapter golden snapshots (`test/golden/adapters-*.edn`) | 87 |

Every record that implements a port (ITranscriber, ISegmenter, ITranslator,
ISubtitleRenderer/Parser, IVideoComposer, IHardsubBurner, IModelHost,
ITranscriptCache, ICoveragePolicy, ISourceReader, native-call strategy) has an
offline port contract, except `JavaCvBurner`, `HardsubComposer` and
`SoftMuxComposer`. Those three cannot be loaded without bytedeco (see E).

## Per-namespace table

| Namespace | Public vars (fn/def + record ctors) | Referenced by a test | T (trifecta files) | C (contract/unit files) | E (excluded, reason) |
|---|---|---|---|---|---|
| `burner.ffmpeg-cli` | 2 (+3 record) | 2/2 | burner_cli_trifecta_test.clj | ffmpeg_cli_test.clj | - |
| `burner.ffmpeg-hw` | 3 (+3 record) | 3/3 | burner_hw_trifecta_test.clj | - | - |
| `burner.ffmpeg-nvenc` | 1 (+0 record) | 1/1 | burner_nvenc_trifecta_test.clj | ffmpeg_nvenc_test.clj | - |
| `burner.javacv` | 0 (+3 record) | 0/0 | - | (records only) — record only; ns `(:import org.bytedeco.javacv ...)` via collect.ffmpeg, so it cannot load without the :ffmpeg alias. Exercised by test-int composer/burner int tests. |
| `codec.block` | 2 (+0 record) | 2/2 | codec_block_trifecta_test.clj | block_test.clj, srt_test.clj, vtt_test.clj | - |
| `codec.dispatch` | 1 (+3 record) | 1/1 | codec_dispatch_trifecta_test.clj, adapters_trifecta_test.clj | dispatch_test.clj, api_compose_ingress_test.clj, api_subtitle_test.clj | - |
| `codec.registry` | 3 (+0 record) | 3/3 | codec_registry_trifecta_test.clj, adapters_trifecta_test.clj | registry_test.clj | - |
| `codec.srt` | 1 (+3 record) | 1/1 | codec_srt_trifecta_test.clj, adapters_trifecta_test.clj | srt_test.clj, corpus_validator_test.clj | - |
| `codec.timecode` | 2 (+0 record) | 2/2 | codec_block_trifecta_test.clj, timecode_trifecta_test.clj, adapters_trifecta_test.clj | block_test.clj, timecode_test.clj | - |
| `codec.vtt` | 1 (+3 record) | 1/1 | codec_vtt_trifecta_test.clj, adapters_trifecta_test.clj | vtt_test.clj | - |
| `composer.both` | 1 (+3 record) | 1/1 | composer_both_trifecta_test.clj | both_test.clj | - |
| `composer.hardsub` | 1 (+3 record) | 0/1 | - | - | `make-composer` — `make-composer`: ns requires burner.javacv -> collect.ffmpeg (bytedeco import), unloadable on the :test classpath; the burner it wraps is contracted via burner_*_trifecta_test. Covered by test-int composer_int_test (:ffmpeg). |
| `composer.softmux` | 1 (+3 record) | 0/1 | - | - | `make-composer` — `make-composer`: ns requires collect.ffmpeg (bytedeco import, raw avformat mux). Covered by test-int composer_int_test (:ffmpeg). |
| `composer.support` | 1 (+0 record) | 1/1 | - | support_test.clj | - |
| `coverage.policies` | 3 (+6 record) | 3/3 | coverage_trifecta_test.clj, gap_filled_trifecta_test.clj, segmenter_gates_trifecta_test.clj | gap_filled_test.clj | - |
| `model-host.speaches` | 2 (+3 record) | 2/2 | - | speaches_test.clj, adapters_port_values_test.clj | - |
| `native-call.caller-thread` | 1 (+3 record) | 1/1 | - | native_call_test.clj | - |
| `native-call.sized-stack` | 2 (+3 record) | 2/2 | - | native_call_test.clj | - |
| `segmenter.gap-filled` | 3 (+3 record) | 3/3 | gap_filled_trifecta_test.clj, segmenter_gates_trifecta_test.clj | gap_filled_test.clj | - |
| `segmenter.silero-vad` | 12 (+3 record) | 12/12 | segmenter_gates_trifecta_test.clj, silero_trifecta_test.clj | silero_vad_test.clj | - |
| `segmenter.silero-vad-native` | 1 (+0 record) | 0/1 | - | - | `speech-probs` — `speech-probs`: imports ai.onnxruntime and builds an ONNX session on a sized native stack. Covered by test-int silero_vad_stack_int_test (:silero-vad). |
| `segmenter.stub` | 2 (+3 record) | 2/2 | grid_trifecta_test.clj, adapters_trifecta_test.clj | gap_filled_test.clj, stub_test.clj, pipeline_test.clj | - |
| `source.file` | 1 (+3 record) | 1/1 | - | file_test.clj, api_compose_ingress_test.clj, api_subtitle_test.clj | - |
| `support.llm-chat` | 7 (+0 record) | 7/7 | llm_chat_trifecta_test.clj, llm_translator_trifecta_test.clj, translator_config_trifecta_test.clj | llm_chat_deadline_test.clj, llm_chat_test.clj, llm_test.clj | - |
| `support.model-catalogue` | 7 (+0 record) | 7/7 | model_catalogue_trifecta_test.clj, translator_config_trifecta_test.clj | llm_test.clj | - |
| `support.secrets` | 3 (+0 record) | 3/3 | translator_config_trifecta_test.clj | secrets_test.clj | - |
| `transcriber.nemo-python` | 7 (+3 record) | 7/7 | nemo_trifecta_test.clj, transcriber_gates_trifecta_test.clj | nemo_python_test.clj, adapters_port_values_test.clj, transcriber_hooks_test.clj | - |
| `transcriber.nemo-python-native` | 1 (+0 record) | 0/1 | - | - | `transcribe-file` — `transcribe-file`: embeds CPython through libpython-clj and loads a NeMo checkpoint. Covered by test-int nemo_canary_int_test (:nemo). |
| `transcriber.onnx-bytedeco` | 2 (+3 record) | 2/2 | onnx_trifecta_test.clj, transcriber_gates_trifecta_test.clj | transcriber_hooks_test.clj | - |
| `transcriber.onnx-bytedeco-native` | 16 (+0 record) | 0/16 | - | - | `session`, `io-names`, `float-tensor`, `long-tensor`, `tensor->floats`, `tensor-shape`, `tensor-data`, `run-graph`, `run1`, `whisper-mel-spec`, `log-mel-spectrogram`, `load-tokenizer`, `detokenize`, `timestamp-segments`, `decode-loop`, `transcribe-wav` — all 16 vars import org.bytedeco.onnxruntime (tensors, sessions, mel spectrogram over JTransforms, tokenizer files). Covered by test-onnx onnx_bytedeco_native_test (:onnx). |
| `transcriber.openai-compatible` | 4 (+3 record) | 4/4 | openai_trifecta_test.clj, transcriber_gates_trifecta_test.clj | openai_compatible_test.clj, adapters_port_values_test.clj, transcriber_hooks_test.clj | - |
| `transcriber.qwen3-asr` | 5 (+3 record) | 5/5 | qwen_trifecta_test.clj, transcriber_gates_trifecta_test.clj | qwen3_asr_test.clj, adapters_port_values_test.clj, transcriber_hooks_test.clj | - |
| `transcriber.qwen3-asr-native` | 1 (+0 record) | 0/1 | - | - | `transcribe-samples` — `transcribe-samples`: requires pretrained.asr (raster compiler, HF weight download). Only the outer gate is contracted. |
| `transcriber.sherpa-onnx` | 4 (+3 record) | 4/4 | sherpa_trifecta_test.clj, transcriber_gates_trifecta_test.clj | sherpa_onnx_test.clj, adapters_port_values_test.clj, transcriber_hooks_test.clj | - |
| `transcriber.sherpa-onnx-native` | 1 (+0 record) | 0/1 | - | - | `transcribe-raw` — `transcribe-raw`: imports com.k2fsa.sherpa.onnx JNI types. The outer record is contracted with this fn stubbed. |
| `transcriber.stub` | 2 (+3 record) | 2/2 | stub_asr_trifecta_test.clj, adapters_trifecta_test.clj | stub_test.clj, transcriber_hooks_test.clj | - |
| `transcriber.support` | 15 (+0 record) | 15/15 | asr_support_trifecta_test.clj, nemo_trifecta_test.clj, openai_trifecta_test.clj, qwen_trifecta_test.clj, sherpa_trifecta_test.clj, silero_trifecta_test.clj, transcriber_gates_trifecta_test.clj, whisper_ffm_trifecta_test.clj, whisper_jni_trifecta_test.clj, adapters_trifecta_test.clj | nemo_python_test.clj, openai_compatible_test.clj, support_test.clj, adapters_port_values_test.clj | - |
| `transcriber.whisper-budget` | 8 (+0 record) | 8/8 | whisper_budget_trifecta_test.clj | whisper_budget_test.clj | - |
| `transcriber.whisper-ffm` | 0 (+3 record) | 0/0 | whisper_ffm_trifecta_test.clj | transcriber_hooks_test.clj | - |
| `transcriber.whisper-jni` | 4 (+3 record) | 4/4 | transcriber_gates_trifecta_test.clj, whisper_jni_trifecta_test.clj | whisper_jni_test.clj, asr_threads_test.clj, transcriber_hooks_test.clj | - |
| `transcriber.whisper-jni-native` | 3 (+0 record) | 0/3 | - | - | `lang-code`, `default-threads`, `transcribe-samples` — `lang-code`/`default-threads`/`transcribe-samples`: ns imports io.github.givimad.whisperjni, so even its pure helpers cannot load without :whisper-jni. |
| `transcript-cache.expiring` | 7 (+3 record) | 7/7 | expiring_trifecta_test.clj, translator_config_trifecta_test.clj | transcript_cache_test.clj | - |
| `transcript-cache.fressian-file` | 2 (+3 record) | 2/2 | translator_config_trifecta_test.clj | transcript_cache_test.clj | - |
| `translator.augment` | 1 (+3 record) | 1/1 | augment_trifecta_test.clj | augment_test.clj | - |
| `translator.chunked` | 2 (+3 record) | 2/2 | chunked_trifecta_test.clj | chunked_test.clj, observed_test.clj | - |
| `translator.identity` | 1 (+3 record) | 1/1 | identity_trifecta_test.clj | identity_test.clj, adapters_port_values_test.clj, api_subtitle_test.clj | - |
| `translator.llm` | 3 (+3 record) | 3/3 | llm_translator_trifecta_test.clj, translator_config_trifecta_test.clj | llm_test.clj | - |
| `translator.observed` | 1 (+3 record) | 1/1 | observed_trifecta_test.clj | observed_test.clj, api_translation_test.clj | - |

## Added in the completion pass

| File | Kind | Subjects |
|---|---|---|
| `segmenter_gates_trifecta_test.clj` | T + C | `silero-vad/make-segmenter` (golden `adapters-silero-make.edn`; knob coercion and defaults over generated opts), the classpath and model gates, every `default-*` knob, `gap-filled/default-policy-key` (wrap tiles an empty clip), `policies/default-min-gap-ms` |
| `transcriber_gates_trifecta_test.clj` | T + C | `sherpa/resolve-model-files` (deterministic discovery over generated temp bundles), `resolve-provider`/`backend-present?`; `nemo/resolve-nemo` (AST refusal for Parakeet), `model-names`, `default-python`; Qwen3 registry gate, `default-model-key`, `known-model-keys`; `onnx/backend-available?`, `default-model-files` layout gate; `openai/make-transcriber`, `provider-defaults`; `whisper-jni/default-model-path`; `support/wav-bytes-slice` (pad/clip arithmetic and WAV byte length) |
| `translator_config_trifecta_test.clj` | T + C | `llm/resolved` (key attached at build, or `:error/translator-unavailable` that names env and pass); `llm-chat/resolve-key` identity, `default-request-timeout-s`, `request-timeout-s`; `catalogue/roles` vs model pins; `fressian-file/default-dir`; `expiring/transcript-key` |
| `whisper_ffm_trifecta_test.clj` | T + C | `WhisperFfmTranscriber` port with `bound-lib`/`run-whisper` stubbed (normalized segments stay inside the raw ones), refusal paths, `:whisper-ffm` resolve gate |
| `schema/adapters_gates.clj` | schema | gate results, constructor values, model-file maps, WAV slice, resolved translator |

No adapter bugs were found in this pass, so there is no `fix(adapters):` commit.

## Full suite

Recorded in the following commit.
