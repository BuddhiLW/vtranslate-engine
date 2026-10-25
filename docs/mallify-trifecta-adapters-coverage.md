# Adapter contract / trifecta coverage

Branch: `swarm/mallify-trifecta-adapters`.

## Status

**Partial implementation, not completion of the requested scope.** No runtime source
or existing `schemas/vtranslate/engine/schema.clj` changed. New schemas live only
under `schemas/`; requiring the engine at runtime does not load Malli.

## Added schema surface

- `schema/port_values.clj`: ordered spans, normalized ASR segments, transcription,
  segmentation, parsed/rendered subtitles, translations, fetched local media,
  single/multiple composition outputs, burner return path, model-host load/list/
  unload values, transcript-cache hit/write/eviction values and Result schemas.
  Results reject simultaneous `:ok` and `:error` keys.
- `schema/adapters_boundaries.clj`: CLI string vectors, verbose ASR replies,
  chat replies, multipart values; `m/=>` declarations for timecode/block helpers,
  ASR path/normalization/offset/clamp/decode-option helpers and grid/stub tiling.
- These are declarative contracts, **not global runtime instrumentation**.
  Boundary wire schemas describe supported fixture shapes, not every provider's
  complete optional JSON vocabulary.

## Added relational trifectas

Every entry below has an `adapters-*.edn` golden, named cases, a bounded generator,
 and a predicate consuming an explicit `{:in ... :out ...}` observation.

| Subject | Relation |
|---|---|
| timecode encode/decode | integer milliseconds round trip through both separators |
| grid-spans | ordered intervals tile the requested duration; no tile exceeds window |
| clamp-to-window | endpoints remain inside window; retained text order matches input |
| offset-segments | inverse offset restores millisecond segment maps |
| codec register/lookup | lookup retrieves the newly registered value, including replacement |
| SRT/VTT through RegistryCodec | render then parse preserves cue times, index and text |
| normalize-segments | output conforms, is idempotent, and never invents extra segments |
| sherpa result mapping | token words preserve order and stay within clip duration |
| Qwen/NeMo language helpers | language mapping is case-insensitive |
| Speaches base URL | removing endpoint then reattaching it recovers the input |

The clock generator is restricted to the parser's documented two-digit hour
range; this does not claim arbitrary-duration round trips. Codec text generators
exclude embedded blank-line delimiters. Offset inversion generation uses integer
milliseconds, avoiding claims of exact inverse floating-point arithmetic.

## Adapter protocol fixtures added

- Stub transcriber and grid segmenter: success values validated against port schemas.
- SRT and VTT: renderer/parser successes and unsupported-format errors validated.
- RegistryCodec: generated full render/parse protocol round trips.
- OpenAI-compatible: real `transcribe` method with in-memory HTTP reply, stubbed
  audio reads/duration; segment order and multipart shape checked. No socket opened.
- Speaches: real model-host methods with stub HTTP request; loaded set, load value,
  and unload status mappings (204/404/409) validated.
- Identity translator: count and positional alignment preserved, including duplicates.
- Disabled cache and unavailable fetcher: default-port outputs validated.
- Explicit negative examples reject inverted ASR intervals, invalid confidence,
  and ambiguous Results.

## Verification

Executed with the requested `local.deps.edn` overlay and `:test` alias:

- Focused: 25 tests, 50 assertions, 0 failures (105-second timeout).
- Full fresh-JVM suite: **847 tests, 3394 assertions, 0 failures**, exit 0.
- Full log: `/tmp/engine-adapters.log`.
- No native aliases, GPU, model download or network required by new tests.
- No production bug fix made; no bug-fix commit warranted by these observations.

## Remaining work / exclusions

Existing per-adapter tests are retained, but they are **not** relabelled as new
schema/trifecta coverage. The following still require explicit work:

- Full public-pure-function inventory across all 48 adapters, providers, collect,
  pipeline and top-level owned namespaces; not every public function has a trifecta.
- Per-adapter schema validation at the *protocol result* for JNI, FFM, ONNX,
  Qwen, NeMo, Silero, decorators, composers, burners and real caches. Existing
  stub-native tests cover many internal paths, but are not proof of this goal.
- Actual fetcher implementations belong to addons; engine supplies unavailable default.
- Broader CLI arg-builder and provider-error JSON schemas and fixtures.
- Coverage/media/source/native-call input and output schemas and function contracts.
- Strict transcript-cache aggregate schema parity with every persisted version.
- Mutation facets are not added to these new trifectas; golden + relational property
  facets run. Do not interpret the macro name as a measured mutation score.

## Effect-only functions deliberately not property-tested here

Effects remain in their existing tests; they are not safe arbitrary generated calls:

- ASR `transcribe`, native `transcribe-raw`, native model/session constructors,
  `backend-present?`, model file discovery/resolution and decoder/model release.
- `support/wav-duration-ms`, `read-wav-mono-floats`, `write-wav-mono!`,
  `wav-bytes-slice` (filesystem/audio reads/writes).
- HTTP transport/key resolution in OpenAI, LLM chat and Speaches; environment,
  password-store and filesystem secrets lookup.
- Composer `compose`, burner `burn!`, collect process runners, ffmpeg/JavaCV
  probes/extractors/encoders and font measurement.
- Fressian file-cache fetch/store/forget/evict, expiration clocks, residency
  `ensure!` and shared-owner state, parallel scheduling and pipeline execution.
- Registry/provider registration and addon loading mutate process-global state.

This is an effect-category list, **not an exhaustive symbol-level audit**. Pure
helpers in the same namespaces are not excused merely because their neighbors
perform IO.
