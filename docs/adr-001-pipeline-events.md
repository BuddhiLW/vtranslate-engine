# ADR-001: Pipeline as FSM stage vectors over hive.events.fsm

- Status: Accepted
- Date: 2026-07-05

## Context

The engine runs two jobs: video translation (`api/run-job`) and subtitle-file
translation (`api/run-subtitle-job`). Both are linear sequences of effectful
steps (media probe, ASR, MT, subtitle render, mux) that must short-circuit on
the first failure and carry a growing context map from step to step. The
`TranslationJob` aggregate additionally has its own lifecycle (JobState) whose
legal transitions must hold independent of how the pipeline drives it.

## Decision

### One generic runner, compiled stage vectors

`vtranslate.engine.pipeline.fsm` is a provider- and domain-agnostic linear
pipeline runner built on `hive.events.fsm` (`src/vtranslate/engine/pipeline/fsm.clj`).
A pipeline is an ordered vector of `JobStage` values (`(stage id handler)`),
compiled once by `compile-stages` into an FSM and executed by `run-pipeline`:

- Each stage is an FSM state keyed by its stage id; the first stage carries
  `pf/start-id` (`::fsm/start`).
- Each stage's handler is `(fn [resources state] => state')` and returns an FSM
  state map wrapping a hive-dsl Result (`{:result ...}`, built via
  `result-state`).
- Transitions are `:dispatches` pairs `[[next-id continue?] [::fsm/end halted?]]`:
  the run advances to the next stage while the carried Result is ok and jumps to
  `::fsm/end` on the first err. The last stage always dispatches to `::fsm/end`.
- `with-result` threads the ok context through a stage body
  (`r/let-ok`-style) and skips the body on an error state.
- `Pipeline` binds a `resources` map (the ports) to a compiled FSM;
  `run-pipeline` returns the final `:result`.

Nothing in the runner knows about media, transcription, translation, or
subtitles — the two engine pipelines are just two stage vectors compiled by it.

### Event vocabulary (stage ids)

The stage ids are namespaced keywords; they are the pipeline's state/event
vocabulary.

Video pipeline (`video-fsm` in `api.clj`):

```
::fsm/start                       start-translation
:vtranslate.pipeline/ingest       ingest-media
:vtranslate.pipeline/transcribe   transcribe-media
:vtranslate.pipeline/extend       apply-extensions
:vtranslate.pipeline/translate    translate-transcript
:vtranslate.pipeline/render       render-subtitles
:vtranslate.pipeline/compose      compose-video
```

Subtitle pipeline (`subtitle-fsm` in `api.clj`):

```
::fsm/start                       start-subtitle-translation
:vtranslate.subtitle/read         read-subtitle-source
:vtranslate.subtitle/parse        parse-subtitle-source
:vtranslate.subtitle/translate    translate-subtitle-cues
:vtranslate.subtitle/render       render-subtitle-track
```

### Fx interpreters (stage handlers)

Stage handlers are the effect interpreters: each receives the `resources` map
and calls the bound ports (`p.media/probe`, `p.media/extract-audio`,
`p.asr/transcribe`, `p.tr/translate-batch`, `p.sub/render-bytes`,
`p.comp/compose`, `p.src/read-text`, `p.sub/parse`, `p.seg/segment`). Pure
calculations live in `vtranslate.engine.calc.*` / domain namespaces and are
invoked from the handlers. Both pipelines share two seams: `start-job`
(validate spec, build MediaAsset + pending TranslationJob) and `finalize-job`
(advance the job, apply the terminal transition, link the subtitle track by id).
`:vtranslate.pipeline/extend` folds registered `:vtranslate.pipeline/pre-translate`
middleware over the context (no-op when no addon is loaded).

`hive.events.fsm` also supports handlers returning `{:data ... :fx [...]}` for
deferred effect execution; the pipeline does not use that mechanism — handlers
return plain data (the Result-wrapping state map) and perform effects inline
through the ports.

### Two ingress paths

- `api/run-job` — video: compiles nothing per call (the FSM is a `def`),
  builds a `Pipeline` over `{media segmenter transcriber translator renderer
  muxer config}` and runs `video-fsm` with the job spec.
- `api/run-subtitle-job` — subtitle file: runs `subtitle-fsm` over
  `{reader parser translator renderer}`. It skips ingestion/ASR, requires an
  explicit source language at the start stage, and finalizes with
  `job/complete` instead of a plain advance.

### JobState FSM (domain lifecycle)

`JobState` (`vtranslate.engine.domain.job`) is a closed ADT:

```
:job/pending :job/ingesting :job/transcribing :job/translating
:job/rendering :job/completed :job/failed
```

Transitions, all rejecting a terminal source with
`:error/illegal-transition {:from variant}`:

- `advance` — move to the next happy-path phase
  (`pending -> ingesting -> transcribing -> translating -> rendering -> completed`).
- `complete` — jump an active job straight to `:job/completed`.
- `fail` — jump an active job to `:job/failed`, carrying a `TranslationError` variant.

`:job/completed` and `:job/failed` are terminal.

The lifecycle is model-checked, not just tested:
`model/vtranslate/engine/model/job_fsm.clj` (recife/TLA+, on the `:model`
extra-path, never required from src) models `advance | complete | fail` over an
arbitrary action sequence and checks two safety invariants via TLC:

- `forward-only` — the phase rank never decreases
  (`pending 0 .. completed 5, failed 6`).
- `terminal-absorbing` — once the previous phase is terminal, the phase cannot change.

`verify` runs the spec through `hive-recife.core/check!` with `:no-deadlock`
(terminal states stutter by design).

## Consequences

- Adding a pipeline step = appending a `(stage id handler)` to the stage
  vector; dispatch/short-circuit wiring is regenerated by `compile-stages`.
- New failure modes surface through the closed `TranslationError` ADT; the
  runner treats every err identically (jump to `::fsm/end`), so no per-stage
  error plumbing exists.
- The two pipelines share the runner, the Result seam, and the
  `start-job`/`finalize-job` seams, but are independent stage vectors — the
  subtitle pipeline can diverge (fewer stages, `complete` terminal step)
  without touching the video path.
- JobState legality is enforced in the domain (`guard-transition`) and
  independently verified by the recife model; the pipeline is one possible
  driver of those transitions.
- The recife model pulls malli, so it is quarantined under `:model` and the
  runtime cores stay malli-free.
