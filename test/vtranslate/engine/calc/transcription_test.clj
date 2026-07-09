(ns vtranslate.engine.calc.transcription-test
  "Promote (CPPB) — golden + property + mutation for build-transcript. Projects
   the Transcript aggregate to plain EDN before snapshotting. No IO."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [hive-test.golden :refer [deftest-golden]]
            [hive-test.mutation :refer [deftest-mutations]]
            [hive-dsl.result :as r]
            [vtranslate.engine.domain.transcription :as tx]
            [vtranslate.engine.calc.transcription :as sut]))

(defn- project
  "Record-free projection of a build-transcript result for snapshotting."
  [res]
  {:ok?       (r/ok? res)
   :indices   (mapv :index (get-in res [:ok :segments]))
   :languages (mapv :language (get-in res [:ok :segments]))
   :status    (get-in res [:ok :status :adt/variant])
   :count     (count (get-in res [:ok :segments]))})

;; =============================================================================
;; GOLDEN — mixed-language / all-default / empty promotion outcomes
;; =============================================================================

(deftest-golden transcription-golden
  "test/golden/transcription-shape.edn"
  {:mixed (project
           (sut/build-transcript
            {:id "tx-1" :asset-id "asset-9" :language "en"
             :segments [{:start-ms 1000 :end-ms 2000 :text "second" :confidence 0.8}
                        {:start-ms 0 :end-ms 1000 :text "first" :confidence 0.9 :language "fr"}
                        {:start-ms 2000 :end-ms 3000 :text "third" :confidence 0.7}]}))
   :all-default (project
                 (sut/build-transcript
                  {:id "tx-2" :asset-id "asset-9" :language "de"
                   :segments [{:start-ms 0 :end-ms 500 :text "eins" :confidence 1.0}
                              {:start-ms 500 :end-ms 1000 :text "zwei" :confidence 0.5}]}))
   :empty (project
           (sut/build-transcript
            {:id "tx-3" :asset-id "asset-9" :language "en" :segments []}))})

;; =============================================================================
;; PROPERTY — real invariants over arbitrary ASR segment-data
;; =============================================================================

(def ^:private gen-lang (gen/elements ["en" "fr" "de" "es" "pt"]))

(def ^:private gen-seg
  (gen/fmap (fn [[s d t c lang?]]
              (cond-> {:start-ms s :end-ms (+ s d) :text t :confidence c}
                lang? (assoc :language lang?)))
            (gen/tuple (gen/choose 0 100000)
                       (gen/choose 0 5000)
                       gen/string-alphanumeric
                       (gen/double* {:min 0.0 :max 1.0 :NaN? false :infinite? false})
                       (gen/one-of [(gen/return nil) gen-lang]))))

;; indices are exactly 1..n, in input order, for any valid segment list
(defspec build-transcript-indices-contiguous 200
  (prop/for-all [segs (gen/vector gen-seg 1 10)]
    (let [res (sut/build-transcript {:id "t" :asset-id "a" :language "en" :segments segs})]
      (and (r/ok? res)
           (= (range 1 (inc (count segs)))
              (map :index (:segments (:ok res))))))))

;; missing per-segment language inherits transcript language; present is kept
(defspec build-transcript-language-inherits 200
  (prop/for-all [segs  (gen/vector gen-seg 1 10)
                 tlang gen-lang]
    (let [res (sut/build-transcript {:id "t" :asset-id "a" :language tlang :segments segs})]
      (and (r/ok? res)
           (= (mapv (fn [s] (or (:language s) tlang)) segs)
              (mapv :language (:segments (:ok res))))))))

;; count is preserved and the aggregate seals to :transcript/complete
(defspec build-transcript-preserves-count 200
  (prop/for-all [segs (gen/vector gen-seg 1 10)]
    (let [res (sut/build-transcript {:id "t" :asset-id "a" :language "en" :segments segs})]
      (and (r/ok? res)
           (= (count segs) (count (:segments (:ok res))))
           (= :transcript/complete (get-in res [:ok :status :adt/variant]))))))

;; =============================================================================
;; UNIT — boundary failure modes fail loud
;; =============================================================================

(deftest empty-segments-fails-asr
  (let [res (sut/build-transcript {:id "t" :asset-id "a" :language "en" :segments []})]
    (is (r/err? res))
    (is (= :error/asr-failed (:error res)))))

(deftest unsupported-transcript-language-fails-loud
  (let [res (sut/build-transcript {:id "t" :asset-id "a" :language "xx"
                                   :segments [{:start-ms 0 :end-ms 1 :text "x" :confidence 0.9}]})]
    (is (= :error/unsupported-language (:error res)))))

(deftest invalid-confidence-fails-loud
  (let [res (sut/build-transcript {:id "t" :asset-id "a" :language "en"
                                   :segments [{:start-ms 0 :end-ms 1 :text "x" :confidence 2.0}]})]
    (is (= :error/invalid-confidence (:error res)))))

(deftest out-of-order-input-still-numbers-1..n
  (let [res (sut/build-transcript
             {:id "t" :asset-id "a" :language "en"
              :segments [{:start-ms 3000 :end-ms 4000 :text "d" :confidence 0.9}
                         {:start-ms 0 :end-ms 1000 :text "a" :confidence 0.9}
                         {:start-ms 2000 :end-ms 3000 :text "c" :confidence 0.9}]})]
    (is (r/ok? res))
    (is (= [1 2 3] (mapv :index (:segments (:ok res)))))))

;; =============================================================================
;; MUTATION — break indexing + language default, prove assertions catch each
;; =============================================================================

(def ^:private mut-segs
  [{:start-ms 1000 :end-ms 2000 :text "b" :confidence 0.8}
   {:start-ms 0 :end-ms 1000 :text "a" :confidence 0.9 :language "fr"}
   {:start-ms 2000 :end-ms 3000 :text "c" :confidence 0.7}])

(defn- build-with
  "Reference build parameterized by the per-segment enrich fn (index+language)."
  [enrich {:keys [id asset-id language segments]}]
  (r/let-ok [t0     (tx/make-transcript {:id id :asset-id asset-id :language language})
             filled (reduce (fn [acc seg-data]
                              (r/let-ok [t   acc
                                         seg (tx/make-segment seg-data)]
                                (r/ok (tx/add-segment t seg))))
                            (r/ok t0)
                            (map-indexed (fn [i seg] (enrich i language seg)) segments))]
    (tx/complete filled)))

(deftest-mutations build-transcript-mutations-caught
  vtranslate.engine.calc.transcription/build-transcript
  [["index-zero-based" (partial build-with
                                (fn [i language seg]
                                  (assoc seg :index i :language (or (:language seg) language))))]
   ["index-constant"   (partial build-with
                                (fn [_ language seg]
                                  (assoc seg :index 1 :language (or (:language seg) language))))]
   ["language-no-default" (partial build-with
                                   (fn [i _language seg]
                                     (assoc seg :index (inc i) :language (:language seg))))]]
  (fn []
    (let [res (sut/build-transcript {:id "t" :asset-id "a" :language "en" :segments mut-segs})]
      (is (r/ok? res))
      (is (= [1 2 3] (mapv :index (:segments (:ok res)))))
      (is (= ["en" "fr" "en"] (mapv :language (:segments (:ok res))))))))
