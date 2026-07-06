(ns vtranslate.engine.adapters.segmenter.stub-test
  "Cutting phase A grid stub — golden + property + mutation on the pure tiler, plus
   the port contract run against the real GridSegmenter (Liskov). No native deps."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [hive-test.golden :refer [deftest-golden]]
            [hive-test.mutation :refer [deftest-mutations]]
            [hive-dsl.result :as r]
            [vtranslate.engine.adapters.segmenter.stub :as sut]
            [vtranslate.engine.wiring :as wiring]
            [vtranslate.engine.contract.ports-contract :as contract]))

;; =============================================================================
;; GOLDEN — the tiling of representative (duration, window) pairs is pure data
;; =============================================================================

(deftest-golden grid-spans-golden
  "test/golden/segmenter-grid-spans.edn"
  {:tiled-clamped (sut/grid-spans 12000 5000)   ;; last span clamped to 12000
   :exact-fit     (sut/grid-spans 10000 5000)
   :single-short  (sut/grid-spans 3000 5000)    ;; one clamped span
   :zero-duration (sut/grid-spans 0 5000)        ;; []
   :zero-window   (sut/grid-spans 12000 0)})     ;; []

;; =============================================================================
;; PROPERTY — spans tile [0, duration) contiguously, clamped, non-overlapping
;; =============================================================================

(defspec grid-spans-tiles-the-timeline 300
  (prop/for-all [duration-ms (gen/choose 1 600000)
                 window-ms   (gen/choose 1 30000)]
    (let [spans (sut/grid-spans duration-ms window-ms)]
      (and (seq spans)
           (= 0 (:start-ms (first spans)))                 ;; starts at origin
           (= duration-ms (:end-ms (last spans)))          ;; final span clamped to duration
           (every? #(<= (:start-ms %) (:end-ms %)) spans)  ;; each well-formed
           (every? #(<= (:end-ms %) duration-ms) spans)    ;; never overshoots
           (->> spans (partition 2 1)                      ;; contiguous + ordered
                (every? (fn [[a b]] (= (:end-ms a) (:start-ms b)))))))))

(defspec grid-spans-empty-on-nonpositive 100
  (prop/for-all [duration-ms (gen/choose -5000 0)
                 window-ms   (gen/choose 1 30000)]
    (= [] (sut/grid-spans duration-ms window-ms))))

;; =============================================================================
;; MUTATION — break the tiler, prove the property assertions catch each variant
;; =============================================================================

(deftest-mutations grid-spans-mutations-caught
  vtranslate.engine.adapters.segmenter.stub/grid-spans
  [["no-clamp"   (fn [d w] (mapv (fn [s] {:start-ms s :end-ms (+ s w)}) (range 0 d w)))]
   ["empty"      (fn [_ _] [])]
   ["off-by-one" (fn [d w] (mapv (fn [s] {:start-ms s :end-ms (min (+ s w) (dec d))}) (range 0 d w)))]]
  (fn []
    (let [spans (sut/grid-spans 12000 5000)]
      (is (seq spans))
      (is (= 12000 (:end-ms (last spans))))
      (is (every? #(<= (:end-ms %) 12000) spans)))))

;; =============================================================================
;; CONTRACT — the real GridSegmenter is substitutable behind ISegmenter (LSP)
;; =============================================================================

(deftest gridsegmenter-satisfies-port-contract
  (contract/check-segmenter (sut/make-segmenter 5000)
                            {:path "/tmp/a.wav" :duration-ms 12000}))

(deftest gridsegmenter-returns-result
  (let [res (sut/grid-spans 12000 5000)]
    (is (= 3 (count res)))
    (is (= {:start-ms 10000 :end-ms 12000} (last res)))))

(deftest build-port-registers-segmenter
  (let [res (wiring/build-port :segmenter {:segment-window-ms 4000})]
    (is (r/ok? res))
    (is (= 4000 (:window-ms (:ok res))))))


(deftest build-port-registers-grid-explicitly
  (let [res (wiring/build-port :segmenter {:segmenter :grid
                                           :segment-window-ms 4000})]
    (is (r/ok? res))
    (is (= 4000 (:window-ms (:ok res))))))
