(ns vtranslate.engine.model.job-fsm
  "recife (TLA+/TLC) model of the JobState lifecycle: forward-only monotonicity +
   terminal absorption over arbitrary advance/complete/fail sequences.

   NON-RUNTIME: requires recife (hive-recife pulls malli), so this ns lives under
   the :model extra-path and is NEVER required from src — the subprocess cores
   stay malli-free. `safety-spec` yields a hive-recife.schema/ModelSpec ready for
   hive-recife.core/check!.

   Lifecycle mirrors vtranslate.engine.domain.job: pending -> ingesting ->
   transcribing -> translating -> rendering -> completed is the happy path
   (advance); complete jumps an active job straight to completed; fail jumps an
   active job to failed. :job/completed and :job/failed are TERMINAL — the domain
   rejects every transition from them (:error/illegal-transition), so the model
   stutters. forward-only: a step never lowers the phase rank. terminal-absorbing:
   once the previous phase was terminal, the phase cannot change."
  (:require [recife.core :as rc]
            [recife.helpers :as rh]
            [hive-recife.core :as hr]))

(def forward
  [:job/pending :job/ingesting :job/transcribing
   :job/translating :job/rendering :job/completed])

(def phase-rank
  "Monotone rank per variant; both terminals sit at the top so a fail from any
   active phase still moves forward, never backward."
  {:job/pending 0 :job/ingesting 1 :job/transcribing 2
   :job/translating 3 :job/rendering 4 :job/completed 5 :job/failed 6})

(def active?
  #{:job/pending :job/ingesting :job/transcribing :job/translating :job/rendering})

(defn advance-phase
  "Next happy-path phase for an active phase, else nil (terminal — no move)."
  [phase]
  (when (active? phase)
    (nth forward (inc (.indexOf forward phase)))))

(def global
  {::phase      :job/pending
   ::prev-phase :job/pending
   ::prev-rank  0})

(rc/defproc job-fsm
  {:procs #{:worker}
   :local {:pc ::step}}
  {[::step {:action #{:advance :complete :fail}}]
   (fn [{:keys [action] :as db}]
     (let [phase (::phase db)
           db*   (assoc db ::prev-phase phase ::prev-rank (phase-rank phase))]
       (case action
         :advance  (if-let [nxt (advance-phase phase)]
                     (assoc db* ::phase nxt)
                     db*)
         :complete (if (active? phase)
                     (assoc db* ::phase :job/completed)
                     db*)
         :fail     (if (active? phase)
                     (assoc db* ::phase :job/failed)
                     db*))))})

(rh/definvariant forward-only [db]
  (>= (phase-rank (::phase db)) (::prev-rank db)))

(rh/definvariant terminal-absorbing [db]
  (if (#{:job/completed :job/failed} (::prev-phase db))
    (= (::phase db) (::prev-phase db))
    true))

(defn safety-spec
  "A hive-recife.schema/ModelSpec for the JobState safety model. Expected result:
   :ok — forward-only + terminal-absorbing hold over every reachable sequence."
  []
  {:name       ::job-fsm-safety
   :init-state global
   :components [job-fsm forward-only terminal-absorbing]
   :safety     ["forward-only" "terminal-absorbing"]
   :liveness   []})

(defn no-deadlock-runner
  "check! effect fn (DIP seam): run the spec through recife with :no-deadlock so
   the absorbing terminal self-loops are not reported as deadlocks. Mirrors
   hive-recife.core/default-runner's IDeref handling."
  [{:keys [init-state components]}]
  (if-let [run-model (requiring-resolve 'recife.core/run-model)]
    (let [ret (run-model init-state (set components) {:no-deadlock true})]
      (if (instance? clojure.lang.IDeref ret) (deref ret) ret))
    hr/recife-absent))

(defn verify
  "Model-check the JobState safety spec.
   => a hive-recife ModelCheckResult (expected {:status :ok ...})."
  []
  (hr/check! (safety-spec) no-deadlock-runner))

(comment
  ;; REPL (needs recife/TLC on the classpath — run under -M:model):
  (verify)
  ;; => {:status :ok :details {:distinct-states .. :generated-states ..}}
  )