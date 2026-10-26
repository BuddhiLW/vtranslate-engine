# Provider and boundary contract coverage

Scope: public `defn` forms in `port/**`, `providers/**`, `collect/**`,
`pipeline/**`, and api, wiring, addons, parallel, residency, version.
Adapter implementation and adapter test trees are owned by the concurrent
engine-adapters-asr ling and are not edited here.

**Covered** = golden/property trifecta; **contract** = explicit behavioral test
(including property/golden tests not packaged as a trifecta). Neither label
claims exhaustive branch/arity coverage. **Excluded** identifies an intentional
boundary not exercised by the core unit contract gate. Test paths below are
relative to `test/vtranslate/engine/`. Namespace prefix is `vtranslate.engine.`.
The inventory was checked with structural-grep over the source forms.

Every new contract trifecta uses named cases, a committed golden path, a
generator, and a predicate combining Malli validation with a relationship
between the input and output observation. Ordinary boundary contract tests do
not pretend that side effects are pure functions. Historical trifectas outside
these contract files retain their original conventions.

## Ports

| Namespace | Covered / contract | Excluded + reason |
|---|---|---|
| port.translator | `translate`, `translator`: port-contract-trifecta-test (delegation and text order) | none |
| port.transcriber | `transcribe*`, `transcriber`, `with-route`, `with-clean`, `cleaned`, `decoded`, `honoured`, `inert-hooks`: port-contract-trifecta-test; hook ordering and decode options also port/transcriber-hooks-test and port/hooks-test. `notice!`: notice-contract captures stderr | none |
| port.native-call | `call-result`: port-rest-contract-trifecta-test (result preservation and throwable conversion); protocol stub also invokes call-native and describe | none |
| port.coverage | `covered`: port-rest-contract-trifecta-test, success/error preservation | none |
| port.transcript-cache | `cache?`: port-rest-contract-trifecta-test; disabled implementation and every protocol operation tested there | none |
| port.fetcher | `fetcher?`, `checked-fetcher`: port-rest-contract-trifecta-test; unavailable implementation and fetch-media stub tested | none |
| port.composer | no public defn; compose protocol stub in port-rest-contract-trifecta-test | no implementation logic to duplicate |
| port.model-host | no public defn; loaded/load!/unload! protocol stubs in port-rest-contract-trifecta-test | no implementation logic to duplicate |
| port.media | no public defn; probe/extract-audio protocol stubs in port-rest-contract-trifecta-test | no implementation logic to duplicate |
| port.source | no public defn; read-text protocol stub in port-rest-contract-trifecta-test | no implementation logic to duplicate |
| port.segmenter | no public defn; segment protocol stub in port-rest-contract-trifecta-test | no implementation logic to duplicate |
| port.burner | no public defn; burn! protocol stub in port-rest-contract-trifecta-test | no implementation logic to duplicate |
| port.subtitle | no public defn; render-bytes/parse protocol stubs in port-rest-contract-trifecta-test | no implementation logic to duplicate |

## Providers

All registry rows below use providers-contract-trifecta-test. Tests install only
a private dispatch key and restore it in finally; they never build a real model.

| Namespace | Covered / contract | Excluded + reason |
|---|---|---|
| providers.registry | `known-keys`, `unknown-error`: generated registry/key/error relationships | none |
| providers.burner-registry | `known`; resolve-burner multimethod default and registered dispatch | none |
| providers.composer-registry | `known`; resolve-composer dispatch; providers/composer-registry-test supplements | none |
| providers.fetcher-registry | `known`; resolve-fetcher dispatch | none |
| providers.segmenter-registry | `known`; resolve-segmenter dispatch; providers/segmenter-registry-test supplements | none |
| providers.transcriber-registry | `known`; resolve-transcriber dispatch | none |
| providers.translator-registry | `known`; resolve-translator dispatch | none |
| providers.coverage-registry | no public defn; resolve-coverage-policy dispatch tested | none |
| providers.native-call-registry | no public defn; resolve-native-call dispatch tested | none |
| providers.router | `resolve-active-transcriber`, `resolve-active-translator`: requested-first, de-duplication, fallback, exhaustion; providers/router-floor-test supplements | none |
| providers.compatibility | `incompatible?`, `check`; default segmentation-required/produced declarations; providers/compatibility-test supplements | none |
| providers.decorators | `register!`, `unregister!`, `contributions`, `decorate`, `identities`: ordering/identity trifecta, with cleanup; providers/decorators-test supplements error paths | none |
| providers.config | `config-path`: explicit override trifecta; `resolve-routing`: providers/config-test covers defaults, file selection and keywise option refinement | Ambient zero-arity config-path environment precedence is not isolated in this process: JVM environment is immutable and machine-dependent; explicit override is the deterministic seam |

## Collect

| Namespace | Covered / contract | Excluded + reason |
|---|---|---|
| collect.units | `us->ms`: collect-contract-trifecta-test and collect/units-test | none |
| collect.audio | `to-probe-info`: collect-contract-trifecta-test; `probe`, `extract-audio`, `probe-all`: collect/audio-test (stub backend, source existence, output identity and positional results) | none |
| collect.media-port | `media-err`, `collect-media-port`: collect-contract-trifecta-test; collect/media-port-test and collect/media-capability-test exercise actual bridge delegation | none |
| collect.port | compatibility `collect-media-port` facade | Excluded from core unit gate: namespace loads JavaCV backend and needs optional :ffmpeg native classpath; injected underlying constructor tested in collect.media-port |
| collect.protocols | no public defn; probe/extract-audio stubs in collect-contract-trifecta-test | no implementation logic to duplicate |
| collect.process | `runner`, `starts?`, `output-of`: collect-contract-trifecta-test; collect/process-test covers actual process environment isolation | none |
| collect.ffmpeg-cli | `ffmpeg-cli`: collect-contract-trifecta-test; `capable?`, `probe`, `burn-hardsub`: collect/ffmpeg-cli-test uses injected runner, checks argv, stream facts, failure and script cleanup | none |
| collect.font-metrics | `awt-font`, `awt-measure`, `measure`: collect-contract-trifecta-test (font fields, width relations, repeatability) | Exact pixel snapshots are host/font dependent; no cross-machine pixel guarantee claimed |
| collect.watermark | `render`: collect-contract-trifecta-test; `png-for`: collect/watermark-contract-test checks real PNG, dimensions, location and cache reuse with temp cleanup | none |
| collect.ffmpeg | native backend | `make-backend`, `auto-font-size`, `burn-hardsub`, `soft-mux` excluded from core gate: namespace imports optional JavaCV/FFmpeg native classes. Real media codec/container behavior belongs to dev/smoke_ffmpeg.clj under :ffmpeg; auto-font-size is a legacy helper in that native-loaded namespace, not loaded just to assert arithmetic |

## Pipeline

| Namespace | Covered / contract | Excluded + reason |
|---|---|---|
| pipeline.fsm | `stage`, `result-state`, `result-of`, `continue?`, `halted?`, `with-result`, `compile-stages`, `pipeline`: pipeline-contract-trifecta-test, including IJobStage/IPipeline stubs and run-pipeline; pipeline/fsm-test supplements | none |
| pipeline.extensions | `contribute!`, `retract!`, `contributed`, `phase-middleware`: pipeline-contract-trifecta-test; middleware multimethod/default and deterministic fold order, cleanup in finally; pipeline/extensions-test supplements | none |

## Top level

| Namespace | Covered / contract | Excluded + reason |
|---|---|---|
| api | `run-job`: api-translation-test, api-code-switch-test, api-silent-media-test, api-compose-test; `run-transcription-job`: api-transcription-test; `run-subtitle-job`: api-subtitle-test; `run-compose-job`: api-compose-ingress-test. These are injected-port orchestration contracts, not pure helpers | no public pure defn to trifecta separately; private stage functions covered through ingress contracts rather than exposed solely for tests |
| wiring | `default-ports`, `transcription-ports`, `parse-ports`: toplevel-contract-trifecta-test asserts calls, port mapping and first-error short circuit. build-port multimethod default also tested; wiring-test covers real dispatch | none |
| addons | `normalize-spec`, `classpath-aliases`: toplevel-contract-trifecta-test; `load-addon!`, `load-addons!`: addons-test (invalid, no-init, missing ns, nil and mapping), addons-catalog-test and addon-visibility-test supplement | none |
| parallel | `attempt`, `fan-out`, `run-each`: toplevel-contract-trifecta-test (indexed item accounting, duplicates, order, values); parallel-test covers timeout, interruption and failures | none |
| residency | `cost-bytes`, `reconcile`, `refusal`, `owner`: toplevel-contract-trifecta-test; `shared-owner`, `ensure!`: residency-test with stub host (reuse, LRU, busy skipping, refusal and unlimited budget) | none |
| version | no public defn; engine-version value validated by toplevel-contract-trifecta-test | observation identity is not a functional version transformation: version is a nonblank release constant |

## Audit additions

The prior area trifectas plus existing contract tests already account for every
public helper in providers and pipeline. The audit added the missing `notice!`
stderr contract (port area) and `png-for` filesystem/cache contract (collect
area), each in a separate small commit. No production source changes required.
