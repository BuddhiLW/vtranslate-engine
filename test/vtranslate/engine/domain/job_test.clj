(ns vtranslate.engine.domain.job-test
  "Job lifecycle — forward-only state machine + total describe."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [hive-test.properties :as props]
            [hive-dsl.result :as r]
            [vtranslate.engine.domain.job :as job]
            [hive-test.mutation :refer [deftest-mutations]]))

(defn- new-job []
  (:ok (job/make-translation-job {:id "j" :asset-id "j-asset" :target-language "en"})))

;; Forward-only: 5 advances walk pending..completed; a 6th is illegal.
(deftest forward-only-lifecycle
  (let [states (take 6 (iterate #(:ok (job/advance %)) (new-job)))]
    (is (= ["pending" "extracting audio" "transcribing"
            "translating" "rendering subtitles" "completed"]
           (mapv job/describe states)))
    (is (r/err? (job/advance (last states)))
        "advancing past :job/completed is an illegal transition")))

;; DDD: the job references the MediaAsset BY ID (asset-id), not by raw path.
(deftest job-links-asset-by-id
  (let [j (new-job)]
    (is (= "j-asset" (:asset-id j)))
    (is (not (contains? j :source)) "the dead :source field is gone")))

;; fail is terminal.
(deftest fail-is-terminal
  (is (= "failed"
         (job/describe (:ok (job/fail (new-job) :error/asr-failed))))))

(def ^:private phases
  "Happy-path phase order — the test-local mirror advance walks."
  [:job/pending :job/ingesting :job/transcribing
   :job/translating :job/rendering :job/completed])

;; DDD: produced aggregates are linked BY ID, each into its own slot.
(deftest links-transcript-and-subtitle-by-id
  (let [j (new-job)]
    (is (= "t-1" (:transcript-id (job/link-transcript j "t-1"))))
    (is (= "s-1" (:subtitle-id (job/link-subtitle j "s-1"))))
    (is (nil? (:subtitle-id (job/link-transcript j "t-1")))
        "link-transcript leaves :subtitle-id untouched")
    (is (nil? (:transcript-id (job/link-subtitle j "s-1")))
        "link-subtitle leaves :transcript-id untouched")))

;; Terminal absorption: :job/completed and :job/failed reject every transition,
;; and the illegal-transition error reports the offending source variant.
(deftest terminal-states-reject-transition
  (let [completed (:ok (job/complete (new-job)))]
    (is (= :job/completed (get-in completed [:state :adt/variant])))
    (is (r/err? (job/fail completed :error/asr-failed)) "completed cannot fail")
    (is (r/err? (job/complete completed))               "completed cannot re-complete")
    (is (r/err? (job/advance completed))                "completed cannot advance"))
  (let [failed (:ok (job/fail (new-job) :error/asr-failed))]
    (is (= :job/failed (get-in failed [:state :adt/variant])))
    (is (r/err? (job/fail failed :error/translation-failed)) "failed cannot re-fail")
    (is (r/err? (job/complete failed))                       "failed cannot complete")
    (is (r/err? (job/advance failed))                        "failed cannot advance"))
  (let [res (job/complete (:ok (job/fail (new-job) :error/asr-failed)))]
    (is (= :error/illegal-transition (:error res)))
    (is (= :job/failed (:from res)) "the error reports the terminal source variant")))

;; Mutation coverage for advance: every hand-mutant of the transition must be
;; killed by the forward-only + terminal-rejection assertions.
(deftest-mutations advance-mutations-caught
  job/advance
  [["off-by-one: stalls in place"
    (fn [job] (r/ok job))]
   ["allow-past-completed: drops the upper-bound guard"
    (fn [job]
      (let [i (.indexOf phases (get-in job [:state :adt/variant]))]
        (r/ok (assoc job :state (job/job-state (get phases (inc i) :job/completed))))))]
   ["allow-from-failed: drops the terminal guard"
    (fn [job]
      (r/ok (assoc job :state (job/job-state :job/ingesting))))]]
  (fn []
    (let [states (take 6 (iterate #(:ok (job/advance %)) (new-job)))]
      (is (= ["pending" "extracting audio" "transcribing"
              "translating" "rendering subtitles" "completed"]
             (mapv job/describe states)))
      (is (r/err? (job/advance (last states)))
          "advancing past :job/completed must be illegal")
      (is (r/err? (job/advance (:ok (job/fail (new-job) :error/asr-failed))))
          "advancing a failed job must be illegal"))))

;; TOTAL: describe returns a string for every JobState variant (runtime exhaustiveness).
(props/defprop-total describe-total
  (fn [variant] (job/describe {:state (job/job-state variant)}))
  (gen/elements [:job/pending :job/ingesting :job/transcribing
                 :job/translating :job/rendering :job/completed :job/failed])
  {:pred string?})