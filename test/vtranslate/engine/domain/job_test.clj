(ns vtranslate.engine.domain.job-test
  "Job lifecycle — forward-only state machine + total describe."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [hive-test.properties :as props]
            [hive-dsl.result :as r]
            [vtranslate.engine.domain.job :as job]))

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
  (is (= "failed" (job/describe (job/fail (new-job) :error/asr-failed)))))

;; TOTAL: describe returns a string for every JobState variant (runtime exhaustiveness).
(props/defprop-total describe-total
  (fn [variant] (job/describe {:state (job/job-state variant)}))
  (gen/elements [:job/pending :job/ingesting :job/transcribing
                 :job/translating :job/rendering :job/completed :job/failed])
  {:pred string?})
