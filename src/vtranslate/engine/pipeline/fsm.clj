(ns vtranslate.engine.pipeline.fsm
  "Generic linear-stage pipeline runner (provider- and domain-agnostic). A pipeline
   is an ordered vector of named stages; each stage threads a hive-dsl Result through
   a shared context map and the run short-circuits to the end on the first :error.
   Nothing here knows about media, transcription, translation, or subtitles — the
   engine's two pipelines (api/run-job, api/run-subtitle-job) are two stage vectors
   compiled by this runner."
  (:require [hive-dsl.result :as r]
            [hive.events.fsm :as fsm]))

(def start-id
  "The FSM entry-state id; the first stage of every pipeline carries it."
  ::fsm/start)

;; ---------------------------------------------------------------------------
;; Stage value object
;; ---------------------------------------------------------------------------

(defprotocol IJobStage
  (stage-id [stage] "The FSM state id this stage occupies.")
  (apply-stage [stage resources state] "Run the stage handler for `resources`/`state`."))

(defrecord JobStage [id handler]
  IJobStage
  (stage-id [_] id)
  (apply-stage [_ resources state]
    (handler resources state)))

(defn stage
  "A named pipeline stage. `handler` is (fn [resources ctx] => Result<ctx'>) — build
   it with `with-result` so it runs only on an ok state and short-circuits otherwise."
  [id handler]
  (->JobStage id handler))

;; ---------------------------------------------------------------------------
;; Result <-> FSM state seam
;; ---------------------------------------------------------------------------

(defn result-state
  "Wrap a hive-dsl Result as an FSM state map."
  [result]
  {:result result})

(defn result-of
  "The hive-dsl Result carried by an FSM state."
  [state]
  (:result state))

(defn continue?
  "Dispatch guard: the carried Result is still ok (advance to the next stage)."
  [state]
  (r/ok? (result-of state)))

(defn halted?
  "Dispatch guard: the carried Result is an error (jump to the end)."
  [state]
  (r/err? (result-of state)))

(defn with-result
  "Thread the ok context of `state`'s Result through `f` (=> Result<ctx'>), rewrapping
   as an FSM state. On an error state `f` is skipped."
  [state f]
  (result-state
   (r/let-ok [ctx (result-of state)]
     (f ctx))))

;; ---------------------------------------------------------------------------
;; Compilation
;; ---------------------------------------------------------------------------

(defn- step-dispatches [next-id]
  [[next-id continue?]
   [::fsm/end halted?]])

(defn- stage-state [stg next-id]
  [(stage-id stg)
   {:handler (fn [resources state]
               (apply-stage stg resources state))
    :dispatches (if next-id
                  (step-dispatches next-id)
                  [[::fsm/end (constantly true)]])}])

(defn compile-stages
  "Compile an ordered stage vector into a runnable FSM: stage k advances to stage
   k+1 while the Result stays ok, jumps to the end on the first error, and ends
   after the last stage. The first stage must carry `start-id`."
  [stages]
  (let [ids      (mapv stage-id stages)
        next-ids (conj (subvec ids 1) nil)]
    (fsm/compile
     {:fsm (into {} (map stage-state stages next-ids))})))

;; ---------------------------------------------------------------------------
;; Runner (DIP seam)
;; ---------------------------------------------------------------------------

(defprotocol IPipeline
  (run-pipeline [pipeline spec] "Run the pipeline from `spec`; => the final hive-dsl Result."))

(defrecord Pipeline [resources fsm]
  IPipeline
  (run-pipeline [_ spec]
    (:result (fsm/run fsm resources {:data spec}))))

(defn pipeline
  "A runnable pipeline binding `resources` to an already-compiled stage `fsm`."
  [resources fsm]
  (->Pipeline resources fsm))
