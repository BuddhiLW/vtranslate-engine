(ns vtranslate.engine.calc.subtitle-in-test
  "No-ASR subtitle-in promoter — golden shape + property (1-based re-indexing,
   order + line preservation) + unit failure modes. Projects the SubtitleTrack
   record to plain data before snapshotting. No IO."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [hive-test.golden :refer [deftest-golden]]
            [hive-dsl.result :as r]
            [vtranslate.engine.domain.rendering :as rd]
            [vtranslate.engine.calc.subtitle-in :as sut]))

(defn- project
  "Record-free projection of a build result for golden snapshotting."
  [res]
  {:ok?       (r/ok? res)
   :indices   (mapv :index (get-in res [:ok :cues]))
   :lines     (mapv :lines (get-in res [:ok :cues]))
   :language  (get-in res [:ok :language])
   :source-id (get-in res [:ok :source-id])
   :extension (when (r/ok? res) (rd/extension (:ok res)))
   :status    (get-in res [:ok :status :adt/variant])})

;; =============================================================================
;; GOLDEN — parser cue-maps (unreliable :index 0 / 5) promote to a 1..n track
;; =============================================================================

(deftest-golden subtitle-in-golden
  "test/golden/subtitle-in-shape.edn"
  (project
   (sut/build-subtitle-track
    [{:index 0 :start-ms 0 :end-ms 1000 :lines ["Hello"]}
     {:index 0 :start-ms 1000 :end-ms 2000 :lines ["World" "second line"]}
     {:index 5 :start-ms 2000 :end-ms 3000 :lines ["Third"]}]
    {:id "sub" :source-id "tc" :language "en" :format :format/srt})))

;; =============================================================================
;; PROPERTY — cues are re-numbered 1..n in order, lines preserved verbatim
;; =============================================================================

(def ^:private gen-cuemap
  (gen/fmap (fn [[s d w]] {:index 0 :start-ms s :end-ms (+ s d) :lines [(str "t" w)]})
            (gen/tuple (gen/choose 0 100000) (gen/choose 1 5000) gen/string-alphanumeric)))

(defspec build-reindexes-and-preserves 100
  (prop/for-all [cues (gen/vector gen-cuemap 1 8)]
    (let [res (sut/build-subtitle-track
               cues {:id "s" :source-id "t" :language "en" :format :format/vtt})]
      (and (r/ok? res)
           (= (range 1 (inc (count cues)))
              (map :index (get-in res [:ok :cues])))
           (= (mapv :lines cues)
              (mapv :lines (get-in res [:ok :cues])))))))

;; =============================================================================
;; UNIT — failure modes fail loud, matching the domain contract
;; =============================================================================

(deftest empty-cues-fails-render
  (let [res (sut/build-subtitle-track [] {:id "s" :source-id "t" :language "en" :format :format/srt})]
    (is (r/err? res))
    (is (= :error/render-failed (:error res)))))

(deftest unsupported-language-fails-loud
  (let [res (sut/build-subtitle-track
             [{:index 0 :start-ms 0 :end-ms 1 :lines ["x"]}]
             {:id "s" :source-id "t" :language "xx" :format :format/srt})]
    (is (= :error/unsupported-language (:error res)))))

(deftest unsupported-format-fails-loud
  (let [res (sut/build-subtitle-track
             [{:index 0 :start-ms 0 :end-ms 1 :lines ["x"]}]
             {:id "s" :source-id "t" :language "en" :format :format/foo})]
    (is (= :error/unsupported-format (:error res)))))

(deftest cue-with-no-text-fails-render
  (let [res (sut/build-subtitle-track
             [{:index 0 :start-ms 0 :end-ms 1000 :lines []}]
             {:id "s" :source-id "t" :language "en" :format :format/srt})]
    (is (= :error/render-failed (:error res)))))

(deftest unreliable-parser-indices-are-renumbered
  (let [res (sut/build-subtitle-track
             [{:index 12 :start-ms 0 :end-ms 1000 :lines ["a"]}
              {:index 12 :start-ms 1000 :end-ms 2000 :lines ["b"]}
              {:index 0 :start-ms 2000 :end-ms 3000 :lines ["c"]}]
             {:id "s" :source-id "t" :language "en" :format :format/srt})]
    (is (r/ok? res))
    (is (= [1 2 3] (mapv :index (get-in res [:ok :cues]))))))
