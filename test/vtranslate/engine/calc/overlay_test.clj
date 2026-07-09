(ns vtranslate.engine.calc.overlay-test
  "Trifecta — golden + property + mutation — for the pure overlay calc: timeline
   projection, active-line pick, and greedy word-wrap. Outputs are plain EDN
   (maps/vectors/strings/longs/nil), so nothing to project. No IO."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [hive-test.golden :refer [deftest-golden]]
            [hive-test.mutation :refer [deftest-mutations]]
            [vtranslate.engine.domain.rendering :as rd]
            [vtranslate.engine.calc.overlay :as sut]))

;; --- builders (real smart-ctors -> unwrap) ---------------------------------

(defn- cue! [m]
  (:ok (rd/make-cue m)))

(defn- track! [cues]
  (reduce rd/add-cue
          (:ok (rd/make-subtitle-track
                {:id "s" :source-id "t" :language "en" :format :format/srt}))
          (map cue! cues)))

(def ^:private sample-tl
  (sut/timeline (track! [{:index 1 :start-ms 0    :end-ms 1000 :lines ["hello"]}
                         {:index 2 :start-ms 1000 :end-ms 2000 :lines ["world" "two"]}
                         {:index 3 :start-ms 3000 :end-ms 4000 :lines ["gap"]}])))

;; =============================================================================
;; GOLDEN — projection sort, inclusive-start/exclusive-end pick, greedy wrap
;; =============================================================================

(deftest-golden overlay-golden
  "test/golden/overlay-shape.edn"
  {:timeline        (sut/timeline
                     (track! [{:index 1 :start-ms 2000 :end-ms 3000 :lines ["c"]}
                              {:index 2 :start-ms 0    :end-ms 1000 :lines ["a" "b"]}
                              {:index 3 :start-ms 1000 :end-ms 2000 :lines ["mid"]}]))
   :active-start    (sut/active-lines sample-tl 0)
   :active-mid      (sut/active-lines sample-tl 500)
   :active-end-excl (sut/active-lines sample-tl 1000)
   :active-gap      (sut/active-lines sample-tl 2500)
   :active-before   (sut/active-lines sample-tl -1)
   :wrap-basic      (sut/wrap-line "the quick brown fox jumps over the lazy dog" 10)
   :wrap-nowrap     (sut/wrap-line "short" 10)
   :wrap-long-word  (sut/wrap-line "supercalifragilistic word" 5)
   :wrap-nil        (sut/wrap-line "a b c" nil)
   :wrap-zero       (sut/wrap-line "a b c" 0)})

;; =============================================================================
;; PROPERTY — generators
;; =============================================================================

(def ^:private gen-word
  (gen/fmap str/join (gen/vector (gen/elements [\a \b \c \d \e]) 1 12)))

(def ^:private gen-text
  (gen/fmap #(str/join " " %) (gen/vector gen-word 0 15)))

(def ^:private gen-raw-cue
  (gen/fmap (fn [[s d ls]]
              {:range {:start {:ms s} :end {:ms (+ s d)}} :lines ls})
            (gen/tuple (gen/choose 0 100000)
                       (gen/choose 0 5000)
                       (gen/vector gen-word 0 3))))

(def ^:private gen-disjoint-tl
  (gen/fmap
   (fn [triples]
     (:acc (reduce (fn [{:keys [t acc]} [gap dur lines]]
                     (let [s (+ t gap) e (+ s dur)]
                       {:t e :acc (conj acc {:start-ms s :end-ms e :lines lines})}))
                   {:t 0 :acc []}
                   triples)))
   (gen/vector (gen/tuple (gen/choose 0 100)
                          (gen/choose 1 100)
                          (gen/vector gen-word 1 3))
               1 8)))

;; --- wrap-line: width bound (except lone over-long word) + word roundtrip ---

(defspec wrap-line-width-and-word-roundtrip 300
  (prop/for-all [text      gen-text
                 max-chars (gen/choose 1 20)]
    (let [lines (sut/wrap-line text max-chars)
          norm  (remove empty? (str/split (str/trim text) #"\s+"))]
      (and (vector? lines)
           (every? (fn [ln]
                     (or (<= (count ln) max-chars)
                         (not (str/includes? ln " "))))
                   lines)
           (= norm (remove empty? (str/split (str/join " " lines) #"\s+")))))))

;; --- timeline: total, count-preserving, start-sorted, lines multiset kept ---

(defspec timeline-sorts-and-preserves 200
  (prop/for-all [cues (gen/vector gen-raw-cue 0 10)]
    (let [tl (sut/timeline {:cues cues})]
      (and (vector? tl)
           (= (count cues) (count tl))
           (apply <= 0 (map :start-ms tl))
           (every? #(and (contains? % :start-ms)
                         (contains? % :end-ms)
                         (vector? (:lines %))) tl)
           (= (frequencies (map (comp vec :lines) cues))
              (frequencies (map :lines tl)))))))

;; --- active-lines: interior t picks its cue; total (nil or a member's lines) ---

(defspec active-lines-covers-interior 200
  (prop/for-all [tl   gen-disjoint-tl
                 idx  gen/nat
                 frac (gen/choose 0 1000)]
    (let [{:keys [start-ms end-ms lines]} (nth tl (mod idx (count tl)))
          t (+ start-ms (mod frac (- end-ms start-ms)))]
      (= lines (sut/active-lines tl t)))))

(defspec active-lines-total 200
  (prop/for-all [tl gen-disjoint-tl
                 t  (gen/choose -50 2000)]
    (let [res (sut/active-lines tl t)]
      (or (nil? res)
          (boolean (some #(= (:lines %) res) tl))))))

;; =============================================================================
;; MUTATION — timeline (sort + line carry), active-lines (boundary), wrap-line
;; =============================================================================

(deftest-mutations timeline-mutations-caught
  vtranslate.engine.calc.overlay/timeline
  [["no-sort"    (fn [track]
                   (mapv (fn [{:keys [range lines]}]
                           {:start-ms (get-in range [:start :ms])
                            :end-ms   (get-in range [:end :ms])
                            :lines    (vec lines)})
                         (:cues track)))]
   ["drop-lines" (fn [track]
                   (->> (:cues track)
                        (map (fn [{:keys [range]}]
                               {:start-ms (get-in range [:start :ms])
                                :end-ms   (get-in range [:end :ms])
                                :lines    []}))
                        (sort-by :start-ms) vec))]
   ["swap-bounds" (fn [track]
                    (->> (:cues track)
                         (map (fn [{:keys [range lines]}]
                                {:start-ms (get-in range [:end :ms])
                                 :end-ms   (get-in range [:start :ms])
                                 :lines    (vec lines)}))
                         (sort-by :start-ms) vec))]]
  (fn []
    (let [tl (sut/timeline
              (track! [{:index 1 :start-ms 2000 :end-ms 3000 :lines ["c"]}
                       {:index 2 :start-ms 0    :end-ms 1000 :lines ["a"]}
                       {:index 3 :start-ms 1000 :end-ms 2000 :lines ["b"]}]))]
      (is (= [0 1000 2000] (mapv :start-ms tl)))
      (is (= [1000 2000 3000] (mapv :end-ms tl)))
      (is (= [["a"] ["b"] ["c"]] (mapv :lines tl))))))

(deftest-mutations active-lines-mutations-caught
  vtranslate.engine.calc.overlay/active-lines
  [["inclusive-end"   (fn [tl t]
                        (some (fn [{:keys [start-ms end-ms lines]}]
                                (when (and (<= start-ms t) (<= t end-ms)) lines))
                              tl))]
   ["exclusive-start" (fn [tl t]
                        (some (fn [{:keys [start-ms end-ms lines]}]
                                (when (and (< start-ms t) (< t end-ms)) lines))
                              tl))]
   ["always-first"    (fn [tl _] (:lines (first tl)))]
   ["always-nil"      (fn [_ _] nil)]]
  (fn []
    (let [tl [{:start-ms 0    :end-ms 1000 :lines ["a"]}
              {:start-ms 1000 :end-ms 2000 :lines ["b"]}]]
      (is (= ["a"] (sut/active-lines tl 0)))
      (is (= ["a"] (sut/active-lines tl 999)))
      (is (= ["b"] (sut/active-lines tl 1000)))
      (is (nil? (sut/active-lines tl 2000))))))

(deftest-mutations wrap-line-mutations-caught
  vtranslate.engine.calc.overlay/wrap-line
  [["no-wrap"           (fn [text _] [text])]
   ["one-word-per-line" (fn [text _]
                          (mapv str (remove str/blank?
                                            (str/split (str/trim text) #"\s+"))))]
   ["strict-width"      (fn [text max-chars]
                          (reduce (fn [lines word]
                                    (let [cur (peek lines)]
                                      (if (and cur (< (+ (count cur) 1 (count word)) max-chars))
                                        (conj (pop lines) (str cur " " word))
                                        (conj lines word))))
                                  []
                                  (remove empty? (str/split (str/trim text) #"\s+"))))]]
  (fn []
    (is (= ["the quick" "brown fox" "jumps over" "the lazy" "dog"]
           (sut/wrap-line "the quick brown fox jumps over the lazy dog" 10)))
    (is (= ["short"] (sut/wrap-line "short" 10)))))
