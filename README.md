# vtranslate-engine

JVM Clojure **domain core** for vtranslate — automated video subtitle translation.
Pipeline: `video → demux audio → ASR (whisper) → machine-translate → render/mux subtitles`.

A thin babashka CLI (`../vtranslate-cli`) drives this engine across a process
boundary (subprocess). **This repo owns all domain types; the CLI owns none.**

## Foundation libs — do NOT reinvent their macros
| Lib | Use it for |
|-----|-----------|
| `hive-dsl` | `defadt`/`adt-case` (closed ADTs), Result monad (`ok`/`err`/`bind`/`let-ok`/`try-effect`) |
| `hive-events` | re-frame-style event/effect pipeline — pure handlers return **effects as data**, IO only in `reg-fx` interpreters |
| `hive-system` | DIP filesystem/path effects (`IFilesystem` / `IPathQuery`) |
| `hive-test` | trifecta generators — golden + property + mutation (test alias) |

## Layers → milestones (Stratified Design × CPPB)
Arrows point down; nothing calls upward. Effects only at the edges (Collect + Boundary).

| Layer | CPPB | Namespaces | Milestone |
|-------|------|-----------|-----------|
| Domain (data) | — | `engine.shared`, `engine.domain.*` | **M1** |
| Calc (pure) | Promote / Pipeline | `engine.calc.*` | M2 |
| Ports (barriers) | — | `engine.port.*` (ITranscriber/ITranslator/ISubtitleRenderer) | M2 |
| API / entrypoint | edge | `engine.api`, `engine.main` | M2 |
| Adapters (boundary) | Boundary | `engine.adapter.{whisper,translator,subtitle}` — `hive-events` fx + `hive-system` | M3 |
| Wiring (compose) | — | `engine.wiring` — DIP default-refs + `reg-fx`, config-driven (OCP) | M3 |
| **Collect** | **Collect** | **`engine.collect.audio` — ffmpeg via JavaCV (bytedeco)** | **M5** |
| Tests | — | contract suites per port + trifecta | M6 |

## ffmpeg (Collect, M5)
In-process via **bytedeco** — no system ffmpeg required (natives are bundled):

- `javacpp-presets/ffmpeg` → raw native bindings (`avformat`/`avcodec`/`swresample`) + bundled ffmpeg natives.
- `javacv` → `FFmpegFrameGrabber` wrapper on top. **Use the grabber by default**; drop to raw `avcodec` only for stream-level control.

Confined to the **Collect layer** (`engine.collect.audio`), behind the boundary —
`engine.domain` / `Cue` never sees a bytedeco type. Traps: grabber is stateful +
**not thread-safe** → one-per-job, `close` in `finally`; convert µs→ms-int at the
collect boundary; pin `javacv` ↔ `presets/ffmpeg` together (ecosystem: javacpp
1.5.10). Native jars: `javacv-platform`/`ffmpeg-platform` (fat, all OSes) or a
`linux-x86_64` classifier (slim). See the `:ffmpeg` alias in `deps.edn`.

## Dev
```bash
clj -P                       # resolve deps (downloads git libs)
clj -M:dev                   # nREPL for REPL-driven dev
clj -M:test                  # run trifecta + contract suites (M6)
clj -M:run run '<edn-job>'   # subprocess entrypoint the CLI calls (M2+)
```
