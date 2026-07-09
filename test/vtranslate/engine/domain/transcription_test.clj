(ns vtranslate.engine.domain.transcription-test
  "Trifecta — golden + property + mutation — for the Transcript aggregate:
   confidence bounds, segment build, add/complete lifecycle, duration calc.
   Records/Results are projected to plain EDN before snapshotting. No IO."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [hive-test.golden :refer [deftest-golden]]
            [hive-test.mutation :refer [deftest-mutations]]
            [hive-dsl.result :as r]
            [vtranslate.engine.shared :as shared]
            [vtranslate.engine.domain.transcription :as sut]))

;; --- projections (record/Result -> plain EDN) ------------------------------

(defn- project-conf [res]
  (if (r/ok? res)
    {:ok? true :score (:score (:ok res))}
    {:ok? false :error (:error res)}))

(defn- project-seg [seg]
  {:index    (:index seg)
   :text     (:text seg)
   :start-ms (:ms (:start (:range seg)))
   :end-ms   (:ms (:end (:range seg)))
   :score    (:score (:confidence seg))
   :language (:language seg)})

(defn- project-transcript [t]
  {:id       (:id t)
   :asset-id (:asset-id t)
   :language (:language t)
   :status   (:adt/variant (:status t))
   :segments (mapv project-seg (:segments t))})

;; --- fixtures (built with the real smart-ctors) ----------------------------

(def ^:private base
  (:ok (sut/make-transcript {:id "tr-1" :asset-id "asset-9" :language "en"})))

(def ^:private seg1
  (:ok (sut/make-segment {:index 0 :start-ms 0 :end-ms 1000
                          :text "Hello" :confidence 0.9 :language "en"})))

(def ^:private seg2
  (:ok (sut/make-segment {:index 1 :start-ms 1000 :end-ms 2500
                          :text "World" :confidence 0.5})))

(def ^:private with-segs (-> base (sut/add-segment seg1) (sut/add-segment seg2)))
(def ^:private completed (:ok (sut/complete with-segs)))

;; =============================================================================
;; GOLDEN — confidence bound table + a fully built, sealed transcript
;; =============================================================================

(deftest-golden confidence-cases-golden
  "test/golden/domain-transcription-confidence.edn"
  (mapv project-conf (map sut/make-confidence [0.0 0.5 1.0 -0.1 1.5 2 "x" nil])))

(deftest-golden transcript-golden
  "test/golden/domain-transcription-shape.edn"
  {:empty     (project-transcript base)
   :partial   (project-transcript with-segs)
   :complete  (project-transcript completed)
   :total-ms  (sut/total-duration-ms completed)})

;; =============================================================================
;; PROPERTY — confidence is ok iff x in [0,1]; duration sums segment lengths
;; =============================================================================

(defspec confidence-ok-iff-in-range 300
  (prop/for-all [x (gen/double* {:NaN? false :infinite? false :min -100.0 :max 100.0})]
    (= (<= 0.0 (double x) 1.0) (r/ok? (sut/make-confidence x)))))

(defspec confidence-in-range-roundtrips-score 200
  (prop/for-all [x (gen/double* {:NaN? false :infinite? false :min 0.0 :max 1.0})]
    (let [res (sut/make-confidence x)]
      (and (r/ok? res) (= (double x) (:score (:ok res)))))))

(defspec non-number-confidence-always-err 100
  (prop/for-all [x (gen/one-of [gen/string gen/keyword (gen/return nil) gen/boolean])]
    (r/err? (sut/make-confidence x))))

(def ^:private gen-seg-spec
  (gen/fmap (fn [[s d c]]
              {:index 0 :start-ms s :end-ms (+ s d) :text "t" :confidence c})
            (gen/tuple (gen/choose 0 100000)
                       (gen/choose 0 10000)
                       (gen/double* {:NaN? false :infinite? false :min 0.0 :max 1.0}))))

(defspec total-duration-sums-segments 200
  (prop/for-all [specs (gen/vector gen-seg-spec 0 12)]
    (let [segs     (map (comp :ok sut/make-segment) specs)
          t        (reduce sut/add-segment base segs)
          expected (reduce + 0 (map #(- (:end-ms %) (:start-ms %)) specs))]
      (and (= (count specs) (count (:segments t)))
           (= expected (sut/total-duration-ms t))))))

(defspec add-segment-grows-and-marks-partial 100
  (prop/for-all [specs (gen/vector gen-seg-spec 1 8)]
    (let [segs (map (comp :ok sut/make-segment) specs)
          t    (reduce sut/add-segment base segs)]
      (and (= (count specs) (count (:segments t)))
           (= :transcript/partial (:adt/variant (:status t)))
           (r/ok? (sut/complete t))))))

;; =============================================================================
;; UNIT — failure modes and lifecycle edges fail loud
;; =============================================================================

(deftest empty-transcript-starts-empty
  (is (= :transcript/empty (:adt/variant (:status base))))
  (is (= 0 (sut/total-duration-ms base))))

(deftest make-transcript-unsupported-language-fails
  (is (r/err? (sut/make-transcript {:id "x" :asset-id "a" :language "xx"})))
  (is (= :error/unsupported-language
         (:error (sut/make-transcript {:id "x" :asset-id "a" :language "xx"})))))

(deftest make-segment-failure-modes
  (is (= :error/inverted-time-range
         (:error (sut/make-segment {:index 0 :start-ms 1000 :end-ms 0
                                    :text "x" :confidence 0.5}))))
  (is (= :error/invalid-confidence
         (:error (sut/make-segment {:index 0 :start-ms 0 :end-ms 100
                                    :text "x" :confidence 2.0}))))
  (is (= :error/unsupported-language
         (:error (sut/make-segment {:index 0 :start-ms 0 :end-ms 100
                                    :text "x" :confidence 0.5 :language "zz"})))))

(deftest complete-empty-fails
  (is (r/err? (sut/complete base)))
  (is (= :error/asr-failed (:error (sut/complete base)))))

(deftest complete-nonempty-seals
  (is (r/ok? (sut/complete with-segs)))
  (is (= :transcript/complete (:adt/variant (:status completed)))))

;; =============================================================================
;; MUTATION — bounds off, sum wrong, complete accepting empty must be caught
;; =============================================================================

(deftest-mutations confidence-bound-mutations-caught
  vtranslate.engine.domain.transcription/make-confidence
  [["accept-above-one"
    (fn [s] (if (and (number? s) (<= 0.0 (double s)))
              (r/ok (sut/->Confidence (double s)))
              (r/err :error/invalid-confidence {:score s})))]
   ["accept-below-zero"
    (fn [s] (if (and (number? s) (<= (double s) 1.0))
              (r/ok (sut/->Confidence (double s)))
              (r/err :error/invalid-confidence {:score s})))]
   ["always-ok"
    (fn [_] (r/ok (sut/->Confidence 0.0)))]]
  (fn []
    (is (r/ok?  (sut/make-confidence 0.0)))
    (is (r/ok?  (sut/make-confidence 1.0)))
    (is (r/ok?  (sut/make-confidence 0.5)))
    (is (r/err? (sut/make-confidence 1.5)))
    (is (r/err? (sut/make-confidence -0.1)))
    (is (r/err? (sut/make-confidence "x")))))

(deftest-mutations total-duration-mutations-caught
  vtranslate.engine.domain.transcription/total-duration-ms
  [["off-by-one"  (fn [t] (inc (reduce + 0 (map #(shared/duration-ms (:range %)) (:segments t)))))]
   ["always-zero" (fn [_] 0)]
   ["count-only"  (fn [t] (count (:segments t)))]]
  (fn []
    (is (= 2500 (sut/total-duration-ms completed)))
    (is (= 0 (sut/total-duration-ms base)))))

(deftest-mutations complete-mutations-caught
  vtranslate.engine.domain.transcription/complete
  [["accept-empty" (fn [t] (r/ok t))]
   ["always-err"   (fn [_] (r/err :error/asr-failed {:reason "x"}))]]
  (fn []
    (is (r/ok?  (sut/complete with-segs)))
    (is (r/err? (sut/complete base)))))
