(ns vtranslate.engine.calc.reflow.linebreak-test
  "The line breaker is exact: its layout costs what the cheapest of ALL layouts
   costs, checked against an exhaustive oracle that prices a layout from the
   documented cost terms alone."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [vtranslate.engine.calc.reflow.linebreak :as sut]))

;; --- the oracle: price any layout, enumerate every layout -------------------

(defn- price
  "The documented cost of showing `lines` (vectors of words) under `limit`."
  [lines max-chars limit language]
  (let [w     sut/default-weights
        weak? (sut/weak-set language)
        bare  #(str/lower-case (str/replace % #"^[^\p{L}\p{N}]+|[^\p{L}\p{N}]+$" ""))
        k     (count lines)]
    (+ (* (:lines w) (dec k))
       (* (:overflow w) (max 0 (- k limit)))
       (reduce + 0.0
               (map-indexed
                (fn [idx line]
                  (let [r (/ (double (count (str/join " " line))) max-chars)]
                    (+ (* (:balance w) r r)
                       (* (:pyramid w) r (/ 1.0 (inc idx)))
                       (if (< (inc idx) k)
                         (let [end (peek line)]
                           (cond (weak? (bare end))             (:weak w)
                                 (re-find #"[,.;:!?…]$" end)    (- (:punct w))
                                 :else                          0.0))
                         0.0))))
                lines)))))

(defn- layouts
  "Every way to cut `words` into consecutive non-empty lines."
  [words]
  (if (= 1 (count words))
    [[words]]
    (let [n (count words)]
      (for [mask (range (bit-shift-left 1 (dec n)))]
        (reduce (fn [lines i]
                  (if (bit-test mask (dec i))
                    (conj lines [(nth words i)])
                    (update lines (dec (count lines)) conj (nth words i))))
                [[(first words)]]
                (range 1 n))))))

(defn- cheapest [words max-chars limit language]
  (->> (layouts words)
       (filter (fn [ls] (every? #(<= (count (str/join " " %)) max-chars) ls)))
       (map #(price % max-chars limit language))
       (apply min)))

(def ^:private gen-words
  (gen/vector (gen/elements ["a" "the" "of" "to" "fox," "quick" "jumps." "lazy"
                             "dog" "market" "I" "went" "yesterday" "and"])
              1 9))

(defspec the-layout-costs-what-the-cheapest-of-all-layouts-costs 300
  (prop/for-all [words gen-words
                 max-chars (gen/choose 10 30)
                 limit (gen/choose 1 3)]
    (let [{:keys [cost]} (sut/break-lines words max-chars {:max-lines limit :language "en"})]
      (< (Math/abs (- cost (cheapest (vec words) max-chars limit "en"))) 1e-9))))

(defspec no-word-is-lost-and-no-line-is-too-wide 300
  (prop/for-all [words gen-words
                 max-chars (gen/choose 4 30)]
    (let [{:keys [lines]} (sut/break-lines words max-chars {:max-lines 2})]
      (and (every? #(<= (count %) max-chars) lines)
           (= (str/join words) (str/replace (str/join lines) " " ""))))))

;; --- what the costs are for -------------------------------------------------

(deftest text-that-fits-one-line-stays-on-one-line
  (is (= ["the quick brown fox"]
         (:lines (sut/break-lines ["the" "quick" "brown" "fox"] 42 {:max-lines 2})))))

(deftest two-lines-are-balanced-where-the-greedy-wrap-leaves-a-stub
  (let [words (str/split "the quick brown fox jumps over the lazy dog" #" ")
        {:keys [lines overflow?]} (sut/break-lines words 30 {:max-lines 2 :language "en"})
        [top bottom] (map count lines)]
    (is (= 2 (count lines)))
    (is (false? overflow?))
    ;; greedy gives 30 over 12
    (is (< (Math/abs (long (- top bottom))) 10))))

(deftest a-line-does-not-end-on-a-word-that-belongs-to-the-next
  (testing "english"
    (is (= ["I went" "to the market"]
           (:lines (sut/break-lines ["I" "went" "to" "the" "market"] 14
                                    {:max-lines 2 :language "en"})))))
  (testing "portuguese, by primary subtag"
    (is (= ["Eu fui" "para a feira"]
           (:lines (sut/break-lines ["Eu" "fui" "para" "a" "feira"] 13
                                    {:max-lines 2 :language "pt-BR"}))))))

(deftest a-break-after-punctuation-is-preferred
  (is (= ["Well, yes," "I suppose so"]
         (:lines (sut/break-lines ["Well," "yes," "I" "suppose" "so"] 14
                                  {:max-lines 2 :language "en"})))))

(deftest a-token-no-line-can-hold-is-cut-by-characters
  (let [{:keys [lines]} (sut/break-lines ["これはとても長い字幕のテキストです"] 8 {:max-lines 3})]
    (is (every? #(<= (count %) 8) lines))
    (is (= "これはとても長い字幕のテキストです" (str/join lines)))))

(deftest more-lines-than-allowed-is-flagged-never-silent
  (let [{:keys [lines overflow?]}
        (sut/break-lines (repeat 6 "abcdefgh") 10 {:max-lines 2})]
    (is (true? overflow?))
    (is (= 6 (count lines)))))

(deftest blank-input-gives-no-lines
  (is (= {:lines [] :cost 0.0 :overflow? false}
         (sut/break-lines ["" "  "] 42 {:max-lines 2}))))
