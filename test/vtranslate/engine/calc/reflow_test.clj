(ns vtranslate.engine.calc.reflow-test
  "Cutting phase B (cue-shaping rule table) — golden + property + mutation on the
   pure reflow pipeline. Cue-maps are plain data, so nothing to project. No IO."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [hive-test.golden :refer [deftest-golden]]
            [hive-test.mutation :refer [deftest-mutations]]
            [vtranslate.engine.calc.reflow :as sut]))

;; --- invariant predicates (shared by properties) ---------------------------

(defn- ordered-nonoverlap? [cs]
  (every? (fn [[a b]] (<= (:end-ms a) (:start-ms b))) (partition 2 1 cs)))

(defn- well-formed? [cs]
  (every? (fn [c] (and (<= (:start-ms c) (:end-ms c))
                       (seq (:lines c))
                       (every? #(and (string? %) (seq %)) (:lines c))))
          cs))

(defn- lines-within? [max-chars cs]
  (every? (fn [c] (every? #(<= (count %) max-chars) (:lines c))) cs))

(defn- one-based-contiguous? [cs]
  (= (map :index cs) (range 1 (inc (count cs)))))

;; =============================================================================
;; GOLDEN — representative rule outputs (plain-data cue-maps, EDN round-trips)
;; =============================================================================

(deftest-golden reflow-golden
  "test/golden/reflow-shape.edn"
  {:merge (sut/reflow [{:index 0 :start-ms 0 :end-ms 1000 :lines ["a"]}
                       {:index 0 :start-ms 500 :end-ms 1500 :lines ["b"]}
                       {:index 0 :start-ms 2000 :end-ms 3000 :lines ["c"]}]
                      {})
   :music (sut/reflow [{:index 0 :start-ms 0 :end-ms 1000 :lines ["( wind howling )"]}
                       {:index 0 :start-ms 1000 :end-ms 2000 :lines ["♪♪♪"]}
                       {:index 0 :start-ms 2000 :end-ms 3000 :lines ["Real speech."]}]
                      {:drop-music? true})
   :wrap  (sut/reflow [{:index 0 :start-ms 0 :end-ms 5000
                        :lines ["the quick brown fox jumps over the lazy dog"]}]
                      {:max-chars-line 20})
   :split (sut/reflow [{:index 0 :start-ms 0 :end-ms 1000
                        :lines ["the quick brown fox jumps over the lazy dog!"]}]
                      {:max-cps 15 :max-chars-line 20 :max-lines 2})
   :timing (sut/reflow [{:index 0 :start-ms 0 :end-ms 990 :lines ["x"]}
                        {:index 0 :start-ms 1000 :end-ms 5000 :lines ["y"]}]
                       {:min-gap-ms 120 :max-dur-ms 2000 :min-dur-ms 500})})

;; =============================================================================
;; PROPERTY — the pipeline output is always a well-formed, ordered, 1-based,
;; width-bounded, non-overlapping cue list (config without :snap keeps the
;; non-overlap invariant — snap deliberately re-quantizes onto a grid).
;; =============================================================================

(def ^:private gen-cue
  (gen/fmap (fn [[s d ws]]
              {:index 0 :start-ms s :end-ms (+ s d)
               :lines [(str/join " " (map #(str "w" %) ws))]})
            (gen/tuple (gen/choose 0 100000)
                       (gen/choose 1 8000)
                       (gen/vector (gen/choose 1 999) 1 12))))

(def ^:private rich-rules
  {:drop-music? false :min-gap-ms 40 :min-dur-ms 500 :max-dur-ms 6000
   :max-chars-line 42 :max-lines 2 :max-cps 18})

(defspec reflow-preserves-invariants 300
  (prop/for-all [cues (gen/vector gen-cue 0 10)]
    (let [out (sut/reflow cues rich-rules)]
      (and (vector? out)
           (well-formed? out)
           (ordered-nonoverlap? out)
           (one-based-contiguous? out)
           (lines-within? 42 out)))))

(defspec reflow-is-total 200
  (prop/for-all [cues (gen/vector gen-cue 0 10)
                 snap  (gen/choose 1 500)]
    (let [out (sut/reflow cues (assoc rich-rules :snap snap))]
      (and (vector? out)
           (well-formed? out)
           (one-based-contiguous? out)
           ;; snap keeps starts ordered even if it can create touching cues
           (apply <= 0 (map :start-ms out))))))

;; =============================================================================
;; Focused unit checks for the two heuristic rules
;; =============================================================================

(deftest music-drop-removes-nonspeech-only
  (let [out (sut/reflow [{:index 0 :start-ms 0 :end-ms 1000 :lines ["( grunts )"]}
                         {:index 0 :start-ms 1000 :end-ms 2000 :lines ["♪ la la ♪"]}
                         {:index 0 :start-ms 2000 :end-ms 3000 :lines ["- ( gasps )"]}
                         {:index 0 :start-ms 3000 :end-ms 4000 :lines ["<i>( music )</i>"]}
                         {:index 0 :start-ms 4000 :end-ms 5000 :lines ["Keep this line."]}]
                        {:drop-music? true})]
    (is (= 1 (count out)) "only the speech cue survives")
    (is (= ["Keep this line."] (:lines (first out))))))

(deftest snap-aligns-boundaries-to-grid
  (let [out (sut/reflow [{:index 0 :start-ms 17 :end-ms 983 :lines ["z"]}] {:snap 100})
        c   (first out)]
    (is (= 0 (:start-ms c)))
    (is (= 1000 (:end-ms c)))
    (is (< (:start-ms c) (:end-ms c)) "snap never collapses a cue")))

(deftest empty-input-yields-empty
  (is (= [] (sut/reflow [] rich-rules))))

;; =============================================================================
;; MUTATION — break the two load-bearing rules, prove the assertions catch each
;; =============================================================================

(deftest-mutations merge-overlaps-mutations-caught
  vtranslate.engine.calc.reflow/merge-overlaps
  [["no-merge"   (fn [cs] (vec cs))]
   ["drop-all"   (fn [_] [])]
   ["keep-first" (fn [cs] (vec (take 1 cs)))]]
  (fn []
    (let [out (sut/reflow [{:index 0 :start-ms 0 :end-ms 1000 :lines ["a"]}
                           {:index 0 :start-ms 500 :end-ms 1500 :lines ["b"]}]
                          {})]
      (is (= 1 (count out)))
      (is (= ["a" "b"] (:lines (first out))))
      (is (= [0 1500] [(:start-ms (first out)) (:end-ms (first out))])))))

(deftest-mutations split-cue-mutations-caught
  vtranslate.engine.calc.reflow/split-cue
  [["no-split" (fn [_ _ _ c] [c])]
   ["drop-cue" (fn [_ _ _ _] [])]]
  (fn []
    (let [out (sut/reflow [{:index 0 :start-ms 0 :end-ms 1000
                            :lines ["alpha bravo charlie delta echo foxtrot golf"]}]
                          {:max-cps 10 :max-chars-line 15 :max-lines 2})]
      (is (> (count out) 1) "a high-CPS cue is split into multiple pieces")
      (is (ordered-nonoverlap? out))
      (is (lines-within? 15 out)))))
