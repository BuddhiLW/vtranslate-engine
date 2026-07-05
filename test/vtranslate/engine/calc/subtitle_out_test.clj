(ns vtranslate.engine.calc.subtitle-out-test
  "Translate-seam promoter (CPPB, pure) — cue-texts is an order-preserving view
   of each cue's joined lines; apply-translations folds a 1:1 translation batch
   back onto the cue-maps (replacing :lines, carrying :index/:start-ms/:end-ms)
   and fails loud on a count mismatch. Unit shape + failure mode + a property
   that count/order/timing survive an equal-length batch. No IO."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [hive-dsl.result :as r]
            [vtranslate.engine.calc.subtitle-out :as sut]))

;; =============================================================================
;; UNIT — cue-texts joins each cue's lines with "\n", cue order preserved
;; =============================================================================

(deftest cue-texts-joins-lines-and-preserves-order
  (let [cues [{:index 1 :start-ms 0    :end-ms 1000 :lines ["Hello" "World"]}
              {:index 2 :start-ms 1000 :end-ms 2000 :lines ["Solo"]}
              {:index 3 :start-ms 2000 :end-ms 3000 :lines ["a" "b" "c"]}]]
    (is (= ["Hello\nWorld" "Solo" "a\nb\nc"] (sut/cue-texts cues)))))

;; =============================================================================
;; UNIT — apply-translations replaces :lines, index + timing carry through 1:1
;; =============================================================================

(deftest apply-translations-replaces-lines-keeps-index-and-timing
  (let [cues [{:index 1 :start-ms 0    :end-ms 1000 :lines ["Hello" "World"]}
              {:index 2 :start-ms 1000 :end-ms 2000 :lines ["Solo"]}]
        res  (sut/apply-translations cues ["Bonjour\nMonde" "Seul"])]
    (is (r/ok? res))
    (is (= [["Bonjour" "Monde"] ["Seul"]] (mapv :lines (:ok res))))
    (is (= [1 2]       (mapv :index (:ok res))))
    (is (= [0 1000]    (mapv :start-ms (:ok res))))
    (is (= [1000 2000] (mapv :end-ms (:ok res))))))

;; =============================================================================
;; UNIT — a count mismatch fails loud (never desync text from timing)
;; =============================================================================

(deftest apply-translations-count-mismatch-fails-loud
  (let [cues [{:index 1 :start-ms 0    :end-ms 1000 :lines ["a"]}
              {:index 2 :start-ms 1000 :end-ms 2000 :lines ["b"]}]
        res  (sut/apply-translations cues ["only one"])]
    (is (r/err? res))
    (is (= :error/translation-failed (:error res)))))

;; =============================================================================
;; PROPERTY — an equal-length batch preserves count, order + start/end timing
;; =============================================================================

(def ^:private gen-cuemap
  (gen/fmap (fn [[idx s d lines]]
              {:index idx :start-ms s :end-ms (+ s d) :lines lines})
            (gen/tuple (gen/choose 0 1000)
                       (gen/choose 0 100000)
                       (gen/choose 1 5000)
                       (gen/vector gen/string-alphanumeric 1 4))))

;; pair a cue with its translation so the two batches are always equal length
(def ^:private gen-cue+translation
  (gen/tuple gen-cuemap gen/string-alphanumeric))

(defspec apply-translations-preserves-count-order-and-timing 100
  (prop/for-all [pairs (gen/vector gen-cue+translation 1 8)]
    (let [cues         (mapv first pairs)
          translations (mapv second pairs)
          res          (sut/apply-translations cues translations)]
      (and (r/ok? res)
           (= (count cues)           (count (:ok res)))
           (= (mapv :index cues)     (mapv :index (:ok res)))
           (= (mapv :start-ms cues)  (mapv :start-ms (:ok res)))
           (= (mapv :end-ms cues)    (mapv :end-ms (:ok res)))))))
