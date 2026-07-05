(ns vtranslate.engine.adapters.transcriber.support-test
  "The normalize-segments contract IS the LSP guarantee for the whole ITranscriber
   family: whatever messy hypotheses a backend emits, the promoted segments are
   ordered, non-overlapping, start<=end, with non-blank string text. If this holds
   here, every adapter that funnels through it satisfies check-transcriber."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [vtranslate.engine.adapters.transcriber.support :as sup]))

(defn- ordered-non-overlapping? [segs]
  (every? (fn [[a b]] (<= (:end-ms a) (:start-ms b))) (partition 2 1 segs)))

(defn- contract-shaped? [segs]
  (and (vector? segs)
       (every? (fn [s] (and (nat-int? (:start-ms s))
                            (<= (:start-ms s) (:end-ms s))
                            (string? (:text s))
                            (seq (:text s)))) segs)
       (ordered-non-overlapping? segs)))

(deftest normalize-hardens-messy-input
  (testing "out-of-order, overlapping, end<start, and blank segments are all repaired"
    (let [raw   [{:start 2.0 :end 1.0 :text "  b  "}    ; end<start + padding
                 {:start 0.0 :end 3.0 :text "a"}        ; overlaps next
                 {:start 1.0 :end 2.5 :text "mid"}      ; overlaps prior
                 {:start 5.0 :end 6.0 :text "   "}      ; blank -> dropped
                 {:start 4.0 :end 5.0 :text "c" :confidence 0.7}]
          out   (sup/normalize-segments raw {:unit :s})]
      (is (contract-shaped? out) "normalized output satisfies the transcriber contract")
      (is (= 4 (count out)) "the blank-text segment is dropped, the rest survive")
      (is (= 700 (long (* 1000 (:confidence (last out)))))  ; 0.7 preserved
          "explicit confidence is preserved"))))

(deftest unit-conversion
  (testing ":s multiplies to ms; :ms passes through; rounding is half-up"
    (is (= 1400 (:end-ms (first (sup/normalize-segments [{:start 0 :end 1.4 :text "x"}] {:unit :s})))))
    (is (= 1400 (:end-ms (first (sup/normalize-segments [{:start-ms 0 :end-ms 1400 :text "x"}] {:unit :ms})))))))

(deftest empty-and-all-blank
  (is (= [] (sup/normalize-segments [] {:unit :s})) "empty in, empty out")
  (is (= [] (sup/normalize-segments [{:start 0 :end 1 :text ""}] {:unit :s})) "all-blank -> empty"))

;; Property: for ANY generated set of ragged segments, the output is always
;; contract-shaped. This is the machine-checked LSP invariant.
(def gen-raw-seg
  (gen/let [a gen/nat, b gen/nat, t (gen/fmap #(apply str %) (gen/vector gen/char-alpha 0 6))]
    {:start-ms (min a b) :end-ms (max a b) :text t}))

(defspec normalized-output-is-always-contract-shaped 200
  (prop/for-all [raw (gen/vector gen-raw-seg 0 40)]
    (contract-shaped? (sup/normalize-segments raw {:unit :ms}))))
