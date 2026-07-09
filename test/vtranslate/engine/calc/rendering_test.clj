(ns vtranslate.engine.calc.rendering-test
  "Trifecta — golden + property + mutation — for the TranslatedCues -> SubtitleTrack promoter."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [hive-test.golden :refer [deftest-golden]]
            [hive-test.mutation :refer [deftest-mutations]]
            [hive-dsl.result :as r]
            [vtranslate.engine.domain.rendering :as rd]
            [vtranslate.engine.domain.translation :as t]
            [vtranslate.engine.calc.rendering :as sut]))

;; --- input builders (real smart-ctors) -------------------------------------

(defn- ->unit [{:keys [start-ms end-ms target-text]}]
  (:ok (t/make-translation-unit {:start-ms start-ms :end-ms end-ms
                                 :source-language "en" :source-text "src"
                                 :target-text target-text})))

(defn- ->tcues
  "Build a completed TranslatedCues aggregate (id \"tc-1\") for `target-lang`."
  [target-lang units]
  (let [tc  (:ok (t/make-translated-cues {:id "tc-1" :transcript-id "tr-1"
                                          :source-language "en"
                                          :target-language target-lang}))
        tc  (reduce t/add-unit (t/begin tc) (map ->unit units))]
    (:ok (t/complete tc))))

(defn- ->empty-tcues [target-lang]
  (:ok (t/make-translated-cues {:id "tc-1" :transcript-id "tr-1"
                                :source-language "en"
                                :target-language target-lang})))

;; --- projection (record-free EDN for snapshotting) -------------------------

(defn- project [res]
  {:ok?       (r/ok? res)
   :format    (when (r/ok? res) (rd/extension (:ok res)))
   :indices   (mapv :index (get-in res [:ok :cues]))
   :lines     (mapv :lines (get-in res [:ok :cues]))
   :language  (get-in res [:ok :language])
   :source-id (get-in res [:ok :source-id])
   :status    (get-in res [:ok :status :adt/variant])})

;; =============================================================================
;; GOLDEN — three units promote to a 1..n rendered track; lang + source-id flow
;; =============================================================================

(deftest-golden rendering-golden
  "test/golden/rendering-shape.edn"
  (project
   (sut/build-subtitle-track
    (->tcues "es" [{:start-ms 0    :end-ms 1000 :target-text "Hola"}
                   {:start-ms 1000 :end-ms 2000 :target-text "mundo"}
                   {:start-ms 2000 :end-ms 3000 :target-text "adios"}])
    {:id "sub-1" :format :format/srt})))

;; =============================================================================
;; PROPERTY — one cue per unit, indices 1..n in order, lines/lang/source-id flow
;; =============================================================================

(def ^:private gen-unit
  (gen/fmap (fn [[s d w]] {:start-ms s :end-ms (+ s d) :target-text (str "t" w)})
            (gen/tuple (gen/choose 0 100000) (gen/choose 0 5000) gen/string-alphanumeric)))

(defspec build-is-contiguous-and-preserves 200
  (prop/for-all [units (gen/vector gen-unit 1 8)]
    (let [res (sut/build-subtitle-track (->tcues "es" units) {:id "s" :format :format/vtt})]
      (and (r/ok? res)
           (= (range 1 (inc (count units)))
              (map :index (get-in res [:ok :cues])))
           (= (mapv (comp vector :target-text) units)
              (mapv :lines (get-in res [:ok :cues])))
           (= "es"   (get-in res [:ok :language]))
           (= "tc-1" (get-in res [:ok :source-id]))
           (= "vtt"  (rd/extension (:ok res)))))))

(defspec build-cue-count-equals-unit-count 100
  (prop/for-all [units (gen/vector gen-unit 1 12)]
    (let [res (sut/build-subtitle-track (->tcues "fr" units) {:id "s" :format :format/srt})]
      (and (r/ok? res)
           (= (count units) (count (get-in res [:ok :cues])))
           (= :track/rendered (get-in res [:ok :status :adt/variant]))))))

;; =============================================================================
;; UNIT — failure modes fail loud
;; =============================================================================

(deftest empty-units-fails-render
  (let [res (sut/build-subtitle-track (->empty-tcues "es") {:id "s" :format :format/srt})]
    (is (r/err? res))
    (is (= :error/render-failed (:error res)))))

(deftest unsupported-format-fails-loud
  (let [res (sut/build-subtitle-track
             (->tcues "es" [{:start-ms 0 :end-ms 1 :target-text "x"}])
             {:id "s" :format :format/foo})]
    (is (= :error/unsupported-format (:error res)))))

(deftest nil-target-text-fails-render
  (let [res (sut/build-subtitle-track
             (->tcues "es" [{:start-ms 0 :end-ms 1000 :target-text nil}])
             {:id "s" :format :format/srt})]
    (is (= :error/render-failed (:error res)))))

;; =============================================================================
;; MUTATION — break the promotion rules, prove the assertions catch each
;; =============================================================================

(defn- mut-check []
  (let [res (sut/build-subtitle-track
             (->tcues "es" [{:start-ms 0    :end-ms 1000 :target-text "a"}
                            {:start-ms 1000 :end-ms 2000 :target-text "b"}
                            {:start-ms 2000 :end-ms 3000 :target-text "c"}])
             {:id "s" :format :format/srt})]
    (is (r/ok? res))
    (is (= [1 2 3] (mapv :index (get-in res [:ok :cues]))))
    (is (= [["a"] ["b"] ["c"]] (mapv :lines (get-in res [:ok :cues]))))
    (is (= "es"   (get-in res [:ok :language])))
    (is (= "tc-1" (get-in res [:ok :source-id])))))

(deftest-mutations unit->cue-mutations-caught
  vtranslate.engine.calc.rendering/unit->cue
  [["index-plus-one" (fn [index unit]
                       (rd/make-cue {:index (inc index)
                                     :start-ms (get-in unit [:range :start :ms])
                                     :end-ms   (get-in unit [:range :end :ms])
                                     :lines [(:target-text unit)]}))]
   ["const-index"    (fn [_index unit]
                       (rd/make-cue {:index 1
                                     :start-ms (get-in unit [:range :start :ms])
                                     :end-ms   (get-in unit [:range :end :ms])
                                     :lines [(:target-text unit)]}))]
   ["wrong-lines"    (fn [index unit]
                       (rd/make-cue {:index index
                                     :start-ms (get-in unit [:range :start :ms])
                                     :end-ms   (get-in unit [:range :end :ms])
                                     :lines ["X"]}))]]
  mut-check)

(deftest-mutations add-unit-mutations-caught
  vtranslate.engine.calc.rendering/add-unit
  [["ignore-cue"   (fn [track-result _pair] track-result)]
   ["add-raw-unit" (fn [track-result [_index unit]]
                     (r/let-ok [track track-result]
                       (r/ok (rd/add-cue track unit))))]]
  mut-check)

(deftest-mutations fill-cues-mutations-caught
  vtranslate.engine.calc.rendering/fill-cues
  [["empty-fill" (fn [track _tc] (r/ok track))]
   ["zero-index" (fn [track tc]
                   (->> (:units tc)
                        (map-indexed (fn [i unit] [i unit]))
                        (reduce #'vtranslate.engine.calc.rendering/add-unit (r/ok track))))]]
  mut-check)
