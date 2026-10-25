# Adapter contract / trifecta coverage

Branch: `swarm/mallify-trifecta-adapters`. Scope: every namespace under
`src/vtranslate/engine/adapters/**` (48 namespaces). Ports, providers, collect,
pipeline and top-level facades are in `docs/mallify-trifecta-providers-coverage.md`.

## Status

**Complete for the adapters scope.** Every public var is referenced by a unit
test, referenced by the opt-in native suite (`test-native/`, see
[Native suite](#native-suite)), or excluded below with the exact artifact this
machine lacks. Nothing is waived. `schemas/vtranslate/engine/schema.clj` is
unchanged. The new schemas are under `schemas/` only (`port_values.clj`,
`adapters_boundaries.clj`, `adapters_gates.clj`, `native_media.clj`,
`native_asr.clj`), and `src` stays malli-free. The native pass found two adapter
bugs, fixed in their own `fix(adapters):` commits (see the native section).

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
- **T/C (native)** — the same T and C kinds, but in `test-native/` under the
  var's own alias. They run the REAL native library on CPU against local
  artifacts, with no network and no GPU. Golden paths are
  `test/golden/native-*.edn`. The default `-M:test` run never loads them.
- **E** — excluded. The var cannot run on this machine even under its alias,
  because a native artifact is missing (named in the table). The integration
  suites that exercise these vars elsewhere (`test-int/`, `test-onnx/`) are
  named too.

## Totals

| | count |
|---|---|
| adapter namespaces | 48 |
| public vars (all) | 253 |
| public fn/def vars (excluding record ctors) | 155 (+1: `softmux/container-language`) |
| referenced by a unit test (`-M:test`) | 129 |
| referenced by the native suite only (`test-native/`) | 23 (`burner.javacv` records, `composer.hardsub` 1, `composer.softmux` 2, `silero-vad-native` 1, `whisper-jni-native` 3, `onnx-bytedeco-native` 16) |
| excluded (E) | 3 (`nemo-python-native/transcribe-file`, `qwen3-asr-native/transcribe-samples`, `sherpa-onnx-native/transcribe-raw`; missing artifacts named in the table) |
| unexplained gaps | 0 |
| adapter golden snapshots (`test/golden/adapters-*.edn`) | 87 |
| native golden snapshots (`test/golden/native-*.edn`) | 9 |

Every record that implements a port (ITranscriber, ISegmenter, ITranslator,
ISubtitleRenderer/Parser, IVideoComposer, IHardsubBurner, IModelHost,
ITranscriptCache, ICoveragePolicy, ISourceReader, native-call strategy) has a
port contract. `JavaCvBurner`, `HardsubComposer` and `SoftMuxComposer` have
theirs in the native `:ffmpeg` suite, and the other records in `-M:test`.

## Per-namespace table

| Namespace | Public vars (fn/def + record ctors) | Referenced by a test | T (trifecta files) | C (contract/unit files) | E (excluded, reason) |
|---|---|---|---|---|---|
| `burner.ffmpeg-cli` | 2 (+3 record) | 2/2 | burner_cli_trifecta_test.clj | ffmpeg_cli_test.clj | - |
| `burner.ffmpeg-hw` | 3 (+3 record) | 3/3 | burner_hw_trifecta_test.clj | - | - |
| `burner.ffmpeg-nvenc` | 1 (+0 record) | 1/1 | burner_nvenc_trifecta_test.clj | ffmpeg_nvenc_test.clj | - |
| `burner.javacv` | 0 (+3 record) | 0/0 | ffmpeg/burner_javacv_test.clj (native, :ffmpeg) | ffmpeg/burner_javacv_test.clj (native) | - |
| `codec.block` | 2 (+0 record) | 2/2 | codec_block_trifecta_test.clj | block_test.clj, srt_test.clj, vtt_test.clj | - |
| `codec.dispatch` | 1 (+3 record) | 1/1 | codec_dispatch_trifecta_test.clj, adapters_trifecta_test.clj | dispatch_test.clj, api_compose_ingress_test.clj, api_subtitle_test.clj | - |
| `codec.registry` | 3 (+0 record) | 3/3 | codec_registry_trifecta_test.clj, adapters_trifecta_test.clj | registry_test.clj | - |
| `codec.srt` | 1 (+3 record) | 1/1 | codec_srt_trifecta_test.clj, adapters_trifecta_test.clj | srt_test.clj, corpus_validator_test.clj | - |
| `codec.timecode` | 2 (+0 record) | 2/2 | codec_block_trifecta_test.clj, timecode_trifecta_test.clj, adapters_trifecta_test.clj | block_test.clj, timecode_test.clj | - |
| `codec.vtt` | 1 (+3 record) | 1/1 | codec_vtt_trifecta_test.clj, adapters_trifecta_test.clj | vtt_test.clj | - |
| `composer.both` | 1 (+3 record) | 1/1 | composer_both_trifecta_test.clj | both_test.clj | - |
| `composer.hardsub` | 1 (+3 record) | 1/1 (native) | ffmpeg/composer_hardsub_test.clj (native, :ffmpeg) | ffmpeg/composer_hardsub_test.clj (native) | - |
| `composer.softmux` | 2 (+3 record) | 2/2 (native) | ffmpeg/composer_softmux_test.clj (native, :ffmpeg) | ffmpeg/composer_softmux_test.clj (native) | - |
| `composer.support` | 1 (+0 record) | 1/1 | - | support_test.clj | - |
| `coverage.policies` | 3 (+6 record) | 3/3 | coverage_trifecta_test.clj, gap_filled_trifecta_test.clj, segmenter_gates_trifecta_test.clj | gap_filled_test.clj | - |
| `model-host.speaches` | 2 (+3 record) | 2/2 | - | speaches_test.clj, adapters_port_values_test.clj | - |
| `native-call.caller-thread` | 1 (+3 record) | 1/1 | - | native_call_test.clj | - |
| `native-call.sized-stack` | 2 (+3 record) | 2/2 | - | native_call_test.clj | - |
| `segmenter.gap-filled` | 3 (+3 record) | 3/3 | gap_filled_trifecta_test.clj, segmenter_gates_trifecta_test.clj | gap_filled_test.clj | - |
| `segmenter.silero-vad` | 12 (+3 record) | 12/12 | segmenter_gates_trifecta_test.clj, silero_trifecta_test.clj | silero_vad_test.clj | - |
| `segmenter.silero-vad-native` | 1 (+0 record) | 1/1 (native) | silero_vad/silero_vad_native_test.clj (native, :ffmpeg:silero-vad) | silero_vad/silero_vad_native_test.clj (native) | - |
| `segmenter.stub` | 2 (+3 record) | 2/2 | grid_trifecta_test.clj, adapters_trifecta_test.clj | gap_filled_test.clj, stub_test.clj, pipeline_test.clj | - |
| `source.file` | 1 (+3 record) | 1/1 | - | file_test.clj, api_compose_ingress_test.clj, api_subtitle_test.clj | - |
| `support.llm-chat` | 7 (+0 record) | 7/7 | llm_chat_trifecta_test.clj, llm_translator_trifecta_test.clj, translator_config_trifecta_test.clj | llm_chat_deadline_test.clj, llm_chat_test.clj, llm_test.clj | - |
| `support.model-catalogue` | 7 (+0 record) | 7/7 | model_catalogue_trifecta_test.clj, translator_config_trifecta_test.clj | llm_test.clj | - |
| `support.secrets` | 3 (+0 record) | 3/3 | translator_config_trifecta_test.clj | secrets_test.clj | - |
| `transcriber.nemo-python` | 7 (+3 record) | 7/7 | nemo_trifecta_test.clj, transcriber_gates_trifecta_test.clj | nemo_python_test.clj, adapters_port_values_test.clj, transcriber_hooks_test.clj | - |
| `transcriber.nemo-python-native` | 1 (+0 record) | 0/1 | - | - | `transcribe-file` — embeds CPython through libpython-clj and loads a NeMo checkpoint. **Missing here:** no Python <= 3.12 with `nemo_toolkit[asr]` (none of the 7 conda envs has `nemo`; `~/anaconda3/envs/vtranslate-asr` does not exist), and no NeMo checkpoint (Canary/Parakeet `.nemo`, otherwise downloaded from NGC/HF). The libpython-clj jar is cached. Covered by test-int nemo_canary_int_test (:nemo) on a box that has both. |
| `transcriber.onnx-bytedeco` | 2 (+3 record) | 2/2 | onnx_trifecta_test.clj, transcriber_gates_trifecta_test.clj | transcriber_hooks_test.clj | - |
| `transcriber.onnx-bytedeco-native` | 16 (+0 record) | 16/16 (native) | onnx/onnx_bytedeco_native_test.clj (native, :ffmpeg:onnx) | onnx/onnx_bytedeco_native_test.clj (native), test-onnx onnx_bytedeco_native_test | - |
| `transcriber.openai-compatible` | 4 (+3 record) | 4/4 | openai_trifecta_test.clj, transcriber_gates_trifecta_test.clj | openai_compatible_test.clj, adapters_port_values_test.clj, transcriber_hooks_test.clj | - |
| `transcriber.qwen3-asr` | 5 (+3 record) | 5/5 | qwen_trifecta_test.clj, transcriber_gates_trifecta_test.clj | qwen3_asr_test.clj, adapters_port_values_test.clj, transcriber_hooks_test.clj | - |
| `transcriber.qwen3-asr-native` | 1 (+0 record) | 0/1 | - | - | `transcribe-samples` — requires pretrained.asr. **Missing here:** (a) the Qwen3-ASR weights (`~/.cache/raster/models` does not exist; `Qwen/Qwen3-ASR-0.6B-hf` is an HF download), and (b) a working raster on this JDK: with the jars cached, the first model load fails compiling `raster/types/promote.clj:16` (`ClassNotFoundException raster.types.promote.TLong` from `defval`) under OpenJDK 27, in a fresh JVM as well. Measured: the nil-samples refusal is an `:error/asr-failed` Result. An unknown model key escapes as `AssertionError`, because `r/try-effect*` does not catch Errors. Only the outer gate is contracted. |
| `transcriber.sherpa-onnx` | 4 (+3 record) | 4/4 | sherpa_trifecta_test.clj, transcriber_gates_trifecta_test.clj | sherpa_onnx_test.clj, adapters_port_values_test.clj, transcriber_hooks_test.clj | - |
| `transcriber.sherpa-onnx-native` | 1 (+0 record) | 0/1 | - | - | `transcribe-raw` — imports com.k2fsa.sherpa.onnx JNI types. **Missing here:** the `com.k2fsa.sherpa.onnx/sherpa-onnx 1.10.30` jar is not in `~/.m2` (`-Spath -M:sherpa` fails resolving its pom offline, 401 from the vtranslate-gitea mirror), and there is no sherpa Whisper export (encoder/decoder/tokens). The outer record is contracted with this fn stubbed. |
| `transcriber.stub` | 2 (+3 record) | 2/2 | stub_asr_trifecta_test.clj, adapters_trifecta_test.clj | stub_test.clj, transcriber_hooks_test.clj | - |
| `transcriber.support` | 15 (+0 record) | 15/15 | asr_support_trifecta_test.clj, nemo_trifecta_test.clj, openai_trifecta_test.clj, qwen_trifecta_test.clj, sherpa_trifecta_test.clj, silero_trifecta_test.clj, transcriber_gates_trifecta_test.clj, whisper_ffm_trifecta_test.clj, whisper_jni_trifecta_test.clj, adapters_trifecta_test.clj | nemo_python_test.clj, openai_compatible_test.clj, support_test.clj, adapters_port_values_test.clj | - |
| `transcriber.whisper-budget` | 8 (+0 record) | 8/8 | whisper_budget_trifecta_test.clj | whisper_budget_test.clj | - |
| `transcriber.whisper-ffm` | 0 (+3 record) | 0/0 | whisper_ffm_trifecta_test.clj | transcriber_hooks_test.clj | - |
| `transcriber.whisper-jni` | 4 (+3 record) | 4/4 | transcriber_gates_trifecta_test.clj, whisper_jni_trifecta_test.clj | whisper_jni_test.clj, asr_threads_test.clj, transcriber_hooks_test.clj | - |
| `transcriber.whisper-jni-native` | 3 (+0 record) | 3/3 (native) | whisper_jni/whisper_jni_native_test.clj (native, :ffmpeg:whisper-jni) | whisper_jni/whisper_jni_native_test.clj (native) | - |
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

No adapter bugs were found in the completion pass. The native pass found two (see below).

## Native suite

`test-native/` is a separate kaocha config (`tests-native.edn`) with one suite
per native alias. It sits on the classpath ONLY through the `:ffmpeg` alias
(`:extra-paths ["test-native"]`, the one deps.edn edit), so `-M:test` neither
discovers nor loads it. Kaocha's `--focus :<suite>` marks the other suites as
skipped before loading them, so a suite whose alias is absent is never required.
Every run is CPU-only and offline. Cap the heap, since the host is shared:

```bash
D="$(cat local.deps.edn)"
clojure -J-Xmx1500m -Sdeps "$D" -M:test:ffmpeg             --config-file tests-native.edn --focus :ffmpeg
clojure -J-Xmx1500m -Sdeps "$D" -M:test:ffmpeg:whisper-jni --config-file tests-native.edn --focus :whisper-jni
clojure -J-Xmx1500m -Sdeps "$D" -M:test:ffmpeg:silero-vad  --config-file tests-native.edn --focus :silero-vad
clojure -J-Xmx1500m -Sdeps "$D" -M:test:ffmpeg:onnx        --config-file tests-native.edn --focus :onnx
```

| Suite | Namespaces under test | Artifacts it reads | Kind |
|---|---|---|---|
| `:ffmpeg` | `burner.javacv` (JavaCvBurner), `composer.hardsub` (make-composer, HardsubComposer), `composer.softmux` (make-composer, SoftMuxComposer, container-language) | none. Each case writes its own 1 s 160x120 10 fps test pattern + 16 kHz mono silence (H.264/AAC) into a temp dir with the bundled bytedeco encoder (`native/media_fixture.clj`) | T + C |
| `:whisper-jni` | `whisper-jni-native` (lang-code, default-threads, transcribe-samples) + the outer WhisperLocalTranscriber port | `VT_WHISPER_MODEL` or `models/ggml-base.bin` or `../models/ggml-base.bin`. Speech: `VT_SPEECH_MEDIA` or `../corpus/campaign-video/jfk-ask-not_24s.mp4` (public domain), decoded by collect.ffmpeg | T + C |
| `:silero-vad` | `silero-vad-native/speech-probs` + the outer SileroVadSegmenter port | `VT_SILERO_MODEL` or `models/silero_vad.onnx` or `../models/silero_vad.onnx`, and the same speech clip | T + C |
| `:onnx` | `onnx-bytedeco-native`, all 16 vars, + the outer OnnxBytedecoTranscriber port | `test-native/fixtures/onnx-tiny-whisper/` (committed, ~1 KB): Whisper-SHAPED encoder/decoder graphs with scripted tokens plus a BPE tokenizer, regenerated by its `make_tiny_whisper.py` (needs the Python `onnx` package). Real ORT sessions, mel, greedy loop, BPE, chunking; only the weights are fake | T + C |

A missing artifact makes its suite FAIL and name the path it looked for. It
never makes the suite skip. Running a native suite claims the artifact is
present.

Relational properties (`:pred` = malli schema from `schema/native_media.clj` /
`schema/native_asr.clj` and the relation):

- **JavaCvBurner / HardsubComposer.** The picture changes on exactly the
  frames a cue covers, per the calc.overlay oracle (per-channel difference
  above re-encode noise). Frame timestamps, duration (within one frame) and
  audio are kept. HardsubComposer adds three things: an explicit registered
  backend is honoured, an unknown one is refused as
  `:error/unknown-burn-backend`, and `:auto` without a capable binary falls
  back to `:javacv`. The output path is the requested one (or the
  `.subbed.mp4` sibling), and no `.part-*` file is left behind. No GPU or
  system-ffmpeg backend ever encodes.
- **SoftMuxComposer.** A/V stream-copied: same codecs and packet counts in the
  same stream slots. Exactly one mov_text stream is appended, tagged with the
  ISO 639-2 code of the track's BCP-47 language. The cues read back exactly
  (start, end, lines joined by newline), ignoring the muxer's empty gap
  samples.
- **whisper-jni-native.** whisper.cpp is not bit-exact from run to run on this
  build: even single-threaded at temperature 0, the text and the timestamps
  drift. So the golden snapshots only run-independent facts: ok, segment
  starts inside the window, ordered, and at least half the words from the
  clip's reference text. Raw ends may overrun a short window, and that is by
  contract: normalize-segments bounds them upstream. Silence and noise never
  yield the clip's words.
- **silero-vad-native.** ONNX Runtime is deterministic, so the probability
  summary is golden. There is one probability per 512-sample (16 kHz) or
  256-sample (8 kHz) window, rounded up. Silence never crosses 0.5; voiced
  speech does.
- **onnx-bytedeco-native.** Each 30 s chunk yields the scripted segments
  shifted by its offset, so a transcript has 2 x ceil(duration / 30 s)
  ordered segments. In `timestamp-segments`, the detokenized words equal the
  joined segment texts: no word is lost or invented.

Bugs the native suite found (each fixed in its own `fix(adapters):` commit):

1. `composer.softmux`: the track's BCP-47 tag (`"pt-BR"`) went straight to the
   mp4 muxer, which keeps only ISO 639-2 codes and silently dropped it, so
   every soft-muxed stream lost its language. New `container-language` maps
   `"pt-BR"` to `"por"` and falls back to `"und"`.
2. `onnx-bytedeco-native/timestamp-segments`: words decoded before the first
   timestamp token were dropped. They are now a segment starting at 0.0.

Still excluded on this machine (artifact missing, see the table):
`nemo-python-native` (no NeMo Python env, no checkpoint), `qwen3-asr-native`
(no weights, and raster fails to compile on OpenJDK 27), `sherpa-onnx-native`
(jar not cached, no export). Also observed: `qwen3-asr-native/transcribe-samples`
lets an unknown model key escape as `AssertionError` instead of an
`:error/asr-failed` Result. Unfixed, because the namespace cannot be tested
here.

Cold run of every suite, each in its own JVM (`-J-Xmx1500m`). All exited 0,
and no golden was (re)written:

| Suite | Result |
|---|---|
| `:ffmpeg` | 11 tests, 26 assertions, 0 failures |
| `:whisper-jni` | 8 tests, 22 assertions, 0 failures |
| `:silero-vad` | 4 tests, 11 assertions, 0 failures |
| `:onnx` | 10 tests, 44 assertions, 0 failures |

## Full suite

Command: `timeout 1600 clojure -Sdeps "$(cat local.deps.edn)" -M:test > /tmp/engine-adapters-final.log 2>&1`
(kaocha `:unit`, `test/` + `schemas/`).

Result: **exit 0 — 1144 tests, 3779 assertions, 0 failures, 0 errors** across 151 test
namespaces, including the four new ones above. No golden snapshot was
(re)written during the run, so every `:golden-path` compared against a committed file.
Re-run after the native pass (the softmux and onnx fixes, the deps.edn path):
**exit 0, the same 1144 tests and 3779 assertions, 0 failures**. `test-native/` is not
on the `-M:test` classpath, so the default suite is unaffected.
