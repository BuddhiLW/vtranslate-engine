# FSM Ingress Parity Audit — video (`api/run-job`) vs subtitle (`api/run-subtitle-job`)

Scope: the two ingress pipelines in `src/vtranslate/engine/api.clj`, both compiled
to `hive.events.fsm` stage vectors by `src/vtranslate/engine/pipeline/fsm.clj`,
checked against the JobState lifecycle in `src/vtranslate/engine/domain/job.clj`
and its recife model `model/vtranslate/engine/model/job_fsm.clj` (invariants:
forward-only, terminal-absorbing). Question: does the no-ASR subtitle ingress
enforce the same silent invariants as the video ingress?

Both pipelines share the `start-job` / `finalize-job` seams (api.clj:47-65) and
the same generic runner: stage k advances to stage k+1 while the carried Result
stays ok, jumps to `::fsm/end` on the first error (pipeline/fsm.clj:71-91).

## Invariant matrix

| # | Invariant | Video (`run-job`) | Subtitle (`run-subtitle-job`) | Status |
|---|-----------|-------------------|-------------------------------|--------|
| 1 | Job created via `job/make-translation-job` in `:job/pending`; target-language validated against the registry | shared `start-job` seam (api.clj:47-57) | same seam (api.clj:243-247) | enforced both |
| 2 | Explicit source-language validated against the registry | late, at transcript build (`tx/make-transcript` → `shared/make-language`; api.clj:98-101, domain/transcription.clj:59) | early, at start stage (api.clj:245-246) | enforced both (different stage) |
| 3 | JobState advances per pipeline stage (phase observability) | advance at ingest (api.clj:81), transcribe (api.clj:102), translate (api.clj:157), double-advance at render via `finalize-job` (api.clj:186) | **no advance at read/parse/translate**; single advance + `job/complete` at render (api.clj:293) | GAP — finding F1 |
| 4 | Terminal state reached only from an active phase (`:error/illegal-transition` otherwise) | `finalize-job` step from `:job/rendering` | `finalize-job` step from `:job/ingesting` | enforced both |
| 5 | `job/fail` transitions the job to `:job/failed` on pipeline error | never called — job dropped on error | never called | GAP in both — finding F2 (parity consistent) |
| 6 | Fail-loud on empty promoted content | empty transcript → `:error/asr-failed` "no segments produced" (domain/transcription.clj:83) | empty cues → `:error/render-failed` "no cues parsed from source" (api.clj:256-259) | enforced both; category mismatch — finding F3 |
| 7 | Translation count mismatch fails loud with `:error/translation-failed {:segment-id :reason}` | `calc.translation` carries both keys (calc/translation.clj:15-19, 46-53) | `calc.subtitle-out/apply-translations` carries `:reason` only (calc/subtitle_out.clj:23-27) | GAP — finding F4 |
| 8 | Pre-translate middleware seam + `:result/extra` merge with reserved-key clobber guard | extend stage + `merge-result-extra` (api.clj:107-118, 165-178, 212) | **absent** — no extension stage in `subtitle-fsm` (api.clj:297-303) | GAP — finding F5 |
| 9 | Auto-ish source-language (`nil`/`""`/`"auto"`/`"multi"`/`"und"`) normalized before reaching the translator | normalized: transcriber gets explicit-or-nil (api.clj:96), translator gets explicit-or-`"und"` via the fallback chain (api.clj:152-153, calc/translation.clj:39-44) | raw spec value forwarded to `translate-batch` | **FIXED** (was GAP — finding F6) |
| 10 | Temp-resource cleanup | extracted-audio temp wav registered `.deleteOnExit` (collect/media_port.clj:20-21) | no temp resources (in-memory text) | n/a — no gap |
| 11 | Result links produced SubtitleTrack by id (`job/link-subtitle`) | `finalize-job` (api.clj:65, 186) | `finalize-job` (api.clj:293) | enforced both |
| 12 | Result shape | `{:spec :job :transcript :translated :subtitle-track :rendered}` (+ middleware `:result/extra`) | `{:job :subtitle-track :rendered}` | by design (no Transcript on the no-ASR path) |

## Findings

### F1 — Subtitle ingress skips per-stage JobState advances — LOW (documented, not fixed)

- Evidence: video advances the job at every stage (api.clj:81, 102, 157, 186).
  Subtitle performs zero advances across read/parse/translate; the job sits in
  `:job/pending` until render, where `finalize-job` advances once
  (pending → ingesting) and completes (api.clj:293, 59-65).
- Impact: terminal result is identical (`:job/completed` via the forward path),
  so the recife invariants (forward-only, terminal-absorbing) hold on both
  paths. The gap is purely observability: a subtitle job never reports
  `:job/translating` / `:job/rendering` phases.
- Why not fixed here: `job/advance` moves strictly to the NEXT forward-path
  phase (domain/job.clj:82-89), so a per-stage advance on the subtitle path
  would mislabel phases (translate would land on `:job/transcribing`). True
  parity needs a domain-level phase-skip (e.g. `job/advance-to`); that is a
  domain API change, not a small safe fix. `docs/c4-code.md:316-317` already
  documents the current behavior as intentional.
- Recommendation: either keep and treat c4-code.md:316-317 as the contract, or
  add a `job/advance-to` domain op and map subtitle stages to phases
  (read+parse → ingesting, translate → translating, render → rendering).

### F2 — Neither ingress transitions the job to `:job/failed` on stage error — LOW (parity consistent)

- Evidence: `job/fail` (domain/job.clj:75-80) has no callers in `src/`; on a
  stage error the FSM jumps to `::fsm/end` (pipeline/fsm.clj:71-81) and the
  in-flight job is discarded — the err channel carries only the error category.
- Impact: both ingresses behave identically, so there is no parity gap between
  them; the gap is between the JobState ADT (which models failure) and the
  pipelines (which never use it). Callers cannot observe a failed job.
- Recommendation: if failed-job observability is wanted, have `run-pipeline`
  (or a boundary wrapper) thread the job through error states and apply
  `job/fail` with the error category before returning. Out of scope for a
  point fix.

### F3 — Empty-parse error mislabeled `:error/render-failed` — LOW (documented, not fixed)

- Evidence: `non-empty-cues` (api.clj:256-259) is invoked in the PARSE stage
  (api.clj:269) but reports `:error/render-failed`. The video path's analogous
  empty-content guard reports the stage-accurate `:error/asr-failed`
  (domain/transcription.clj:83).
- Impact: fail-loud behavior is at parity (both short-circuit); only the
  category misleads boundary consumers about which stage failed.
- Why not fixed: the correct category (`:error/parse-failed`) does not exist in
  the closed TranslationError ADT (domain/job.clj:35-52); adding a variant
  changes the public error contract — a taxonomy decision, not a point fix.
- Recommendation: add `[:error/parse-failed {:reason string?}]` to
  TranslationError and use it in `non-empty-cues`.

### F4 — Subtitle count-mismatch error misses `:segment-id` — LOW (documented, not fixed)

- Evidence: TranslationError declares `:error/translation-failed` as
  `{:segment-id string? :reason string?}` (domain/job.clj:48) and the
  ITranslator contract repeats it (port/translator.clj:10). The video path
  honors both keys (calc/translation.clj:15-19, 46-53); the subtitle path's
  `apply-translations` emits `{:reason}` only (calc/subtitle_out.clj:23-27).
- Why not fixed: `apply-translations` is a pure 2-arity calc with several
  direct test callers; threading an id through is a signature change across
  the promote layer, not a point fix.
- Recommendation: add an `:id` (or opts-map) parameter to
  `c.so/apply-translations` and pass `job-id` from `translate-subtitle-cues`.

### F5 — Subtitle ingress has no middleware / `:result/extra` seam — MEDIUM (documented, not fixed)

- Evidence: `subtitle-fsm` (api.clj:297-303) has no stage equivalent to
  `:vtranslate.pipeline/extend` (api.clj:212), so `ext/middleware
  :vtranslate.pipeline/pre-translate` never runs for subtitle jobs and
  `:result/extra` is never merged (no `merge-result-extra` call).
- Impact: addons registered on the pre-translate seam silently do nothing for
  the subtitle ingress; a user expecting addon behavior (e.g. comprehension
  grounding) on `run-subtitle-job` gets none, with no error.
- Why not fixed: whether addons SHOULD apply to the subtitle ingress is a
  product/scope decision (the seam may be video-only by design).
- Recommendation: decide scope; if addons apply, add an extend stage between
  parse and translate plus the `merge-result-extra` guard at render.

### F6 — Auto-ish source-language reached the subtitle translator raw — FIXED

- Evidence (pre-fix): `translate-subtitle-cues` forwarded the spec's
  `source-language` verbatim to `p.tr/translate-batch` (api.clj:278-279), so
  `"auto"` / `""` arrived at the translator as a language token. The video
  path never does this: the transcriber gets `explicit-source-language`
  (api.clj:96) and the translator's fallback chain bottoms out at `"und"`
  (api.clj:152-153, calc/translation.clj:39-44).
- Fix applied: `translate-subtitle-cues` now passes
  `(explicit-source-language source-language)` (api.clj:278-280) — explicit
  tags pass through untouched; auto-ish tokens normalize to nil (auto-detect),
  matching what the video path hands its transcriber and what the LLM
  translator already accepts (`system-prompt` handles a nil source).
- Test: `auto-source-language-normalized-for-translator` in
  `test/vtranslate/engine/api_subtitle_test.clj`.

## Recife model cross-check

`model/vtranslate/engine/model/job_fsm.clj` models advance/complete/fail over
the phase rank with `forward-only` + `terminal-absorbing` invariants. Both
ingress paths are specializations of the modeled action set (video = advance×5,
subtitle = advance + complete), so both inherit the model's safety result. F2
notes the model's `:fail` action is unreachable from the pipelines today.
