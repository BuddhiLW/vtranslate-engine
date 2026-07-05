# vtranslate-engine

JVM Clojure **domain core** for vtranslate — automated video subtitle translation.
It carries the whole domain model (DDD aggregates + a shared kernel), the pure
pipeline, and the driven adapters. A thin babashka CLI (`../vtranslate-cli`) drives
it across a process boundary (subprocess EDN transport). **This repo owns all
domain types; the CLI owns none.**

Two ingress paths:

- **Ingress A (media, with ASR):** `video/audio → demux audio → ASR → machine-translate → render subtitles (SRT/VTT)`.
- **Ingress B (subtitle, no ASR):** `subtitle file → parse → (optional reflow) → machine-translate → re-render (SRT/VTT)`.

Ingress B is the **fully runnable** path today (plain classpath, no bytedeco, no
ASR). Ingress A's media Collect, translate, and render stages are done but the
path is blocked on a real ASR adapter (the transcriber is still a fail-loud stub).

## Domain model (M0) — 5 bounded contexts + shared kernel

All aggregates are `hive-dsl` `defadt` (closed sums) + `defrecord` value objects
behind **smart constructors** that return a Result — validation lives at
construction, invalid states are unrepresentable. **No Malli** on the engine
classpath. DDD: cross-aggregate references are **by id**, never embedded.

| Bounded context | Aggregate root | Also | Lifecycle ADT |
|-----------------|----------------|------|---------------|
| `domain.ingestion` | `MediaAsset` | `ProbeInfo` VO, `MediaKind` | `AssetStatus` |
| `domain.job` | `TranslationJob` | `TranslationError` (closed err set) | `JobState` (forward-only FSM) |
| `domain.transcription` | `Transcript` | `Segment` entity, `Confidence` VO | `TranscriptStatus` |
| `domain.translation` | `TranslatedCues` | `TranslationUnit` VO | `TranslationStatus` |
| `domain.rendering` | `SubtitleTrack` | `Cue` entity, `SubtitleFormat` | `TrackStatus` |

**Shared kernel** (`engine.shared`, bottom of the stack — depends on nothing
above): `Language` (BCP-47, closed registry), `Timecode`, `TimeRange`, `SourceRef`.

## Architecture — CPPB strata

Arrows point down; nothing calls upward. Effects only at the edges (Collect +
Boundary); the middle is pure.

| Stratum | Realized by | Role |
|---------|-------------|------|
| **Collect** | `engine.collect.*` (`hive-system` fs + `hive-weave` bounded concurrency; JavaCV ffmpeg) | path/process effects — probe container facts, demux audio to PCM/WAV |
| **Promote** | `engine.calc.*` (pure) | lift boundary DATA (ASR segments, parsed cue-maps, translations) into domain aggregates — no IO |
| **Pipeline** | `engine.api` | orchestrate one job over INJECTED ports — a `hive-dsl` Result **railway** today (first err short-circuits); `hive-events` + a `JobState` FSM are planned |
| **Boundary** | `engine.port.*` / `engine.adapters.*` / `engine.providers.*` | ports = `defprotocol`; driven adapters = `defrecord`; the DIP seam is wired by `engine.wiring` (open `build-port` defmulti, OCP) and entered from `engine.main` |

## Foundation libs — do NOT reinvent their macros

| Lib | Use it for |
|-----|-----------|
| `hive-dsl` | `defadt`/`adt-case` (closed ADTs) + smart ctors; Result railway (`ok`/`err`/`let-ok`/`try-effect`) — every fallible fn |
| `hive-system` | Collect: DIP filesystem/path effects (`fs/exists?`) |
| `hive-weave` | Collect: bounded concurrency (`bounded-pmap`) — one stateful ffmpeg grabber per task |
| `hive-di` | provider routing: typed EDN/env config resolution (required via `.source`/`.resolve` only, so Malli is never dragged in) |
| `hive-events` | **planned** — event/effect pipeline to drive the `JobState` FSM (not yet wired) |
| `hive-test` | test alias: trifecta generators (golden + property + mutation) |

## Status

Done:

- **M0** — domain model: all 5 bounded contexts + shared kernel (`defadt`/`defrecord` + smart ctors).
- **M2** — subtitle codecs: SRT + WebVTT render/parse (`engine.adapters.codec.*`), selected per call by a format→codec registry (OCP); pure text ↔ cue-map, no IO.
- **M4** — provider routing (config → registry → router, fail-loud) + translator adapters: `:identity` passthrough terminus and an OpenAI-compatible LLM translator (`:openrouter` / `:venice`, order/count-preserving, key from `pass:` or env).
- **M5** — Collect: in-process ffmpeg via bytedeco **JavaCV** (probe + audio extract), behind the media port — no bytedeco type crosses the boundary, µs→ms converted at the edge.
- **M6** — **Ingress B** (no-ASR): subtitle → translate → re-render, runs end-to-end WITHOUT any ASR adapter or bytedeco. Optional pure `calc.reflow` cutting stage (drop-music / merge / cap / split-CPS / snap / re-index).

Not yet:

- **ASR transcriber** — still a fail-loud stub; no real adapter. The transcriber registry/router legitimately exhaust and return `:error/no-transcriber-available` (ASR **never** falls back to a fake transcript). This blocks Ingress A.
- **`hive-events` JobState FSM** — the pipeline is the `hive-dsl` railway for now.

## ffmpeg (Collect, M5)

In-process via **bytedeco** — no system ffmpeg required (natives bundled):

- `javacpp-presets/ffmpeg` → raw native bindings (`avcodec`) + bundled natives.
- `javacv` → `FFmpegFrameGrabber`/`FFmpegFrameRecorder` wrappers. Use the grabber
  by default; drop to raw `avcodec` only for stream-level constants.

Confined to `engine.collect.*`, behind `port.media` — `engine.domain` never sees a
bytedeco type. Traps encoded in the adapter: the grabber is stateful + **not
thread-safe** → one-per-task, `with-open` closes on throw; ffmpeg counts in
**microseconds** → convert to integer ms at the boundary; pin `javacv` ↔
`presets/ffmpeg` together. See the `:ffmpeg` alias in `deps.edn` (slim
`linux-x86_64` natives; swap the classifier for other platforms).

## Dev

```bash
clj -P                 # resolve deps (downloads git libs)
clj -M:dev             # nREPL for REPL-driven dev
clj -M:test            # kaocha: unit + property + contract suites

# Ingress B (no ASR) — runs on the plain classpath. The spec is one EDN map on argv
# (or stdin); a subtitle extension selects this path. Prints an EDN Result, exit 0/1.
clj -M:run '{:job-id "j1" :source "in.srt" :source-language "en" :target-language "pt-BR" :format :format/srt}'
#   default translator is :identity (structural passthrough smoke run); for real MT
#   set VT_TRANSLATOR=openrouter (or :config {:translator :openrouter} in the spec).

# Ingress A (demux + ASR) needs the :ffmpeg alias for media AND a real ASR adapter
# (not yet shipped — fails loud with :error/no-transcriber-available until one lands).
clj -M:ffmpeg:run '{:job-id "j1" :source "in.mp4" :source-language "en" :target-language "pt-BR"}'
```
