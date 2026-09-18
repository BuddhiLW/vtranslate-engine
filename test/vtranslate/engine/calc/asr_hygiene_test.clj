(ns vtranslate.engine.calc.asr-hygiene-test
  "Pure unit tests for the decoder-loop collapse functions."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [hive-test.trifecta :refer [deftrifecta]]
            [vtranslate.engine.calc.asr-hygiene :as h]))

(deftest normalize-text
  (is (= "go go go" (h/normalize-text "  Go, go... GO!"))
      "lower-case, punctuation replaced by space, trimmed")
  (is (= "" (h/normalize-text nil))
      "nil becomes empty string"))

(deftest foreign-speech-placeholders-in-any-casing-or-bracket-style
  (doseq [t ["(speaking foreign language)" "[SPEAKING FOREIGN LANGUAGE]"
             "(Speaking in foreign language)" "*speaks foreign language*"
             "[foreign language]" "  ( speaking  foreign language )  "]]
    (is (= {:kind :foreign-speech :language-hint nil} (h/classify-placeholder t)) t))
  (is (= {:kind :foreign-speech :language-hint "de"} (h/classify-placeholder "[speaking German]")))
  (is (= {:kind :foreign-speech :language-hint "de"} (h/classify-placeholder "(SPEAKING GERMAN)")))
  (is (= {:kind :foreign-speech :language-hint "es"} (h/classify-placeholder "[in Spanish]"))))

(deftest sound-placeholders
  (doseq [t ["[Music]" "(music)" "(upbeat music playing)" "*applause*" "[BLANK_AUDIO]"
             "♪♪" "♪ ♪" "(Alle lachen)" "[Applaus]" "(risos)"]]
    (is (= {:kind :sound} (h/classify-placeholder t)) t)))

(deftest speech-is-not-a-placeholder
  (doseq [t ["Ich bin ein Berliner" "I take pride in the words \"Ich bin ein Berliner.\""
             "Hello (laughs)" "[en segment 1]" "Music is my life" ""
             "(a very long parenthetical remark about music and life)"]]
    (is (nil? (h/classify-placeholder t)) t)))

(deftest mark-placeholder-keeps-the-text-and-says-what-it-is
  (is (= {:text "[speaking German]" :start 0 :end 1
          :asr/placeholder :foreign-speech :asr/language-hint "de"}
         (h/mark-placeholder {:text "[speaking German]" :start 0 :end 1})))
  (is (= {:text "[Music]" :asr/placeholder :sound} (h/mark-placeholder {:text "[Music]"})))
  (is (= {:text "hi"} (h/mark-placeholder {:text "hi"})) "speech is untouched")
  (is (h/placeholder? {:text "anything" :asr/placeholder :sound}) "the mark alone suffices"))

(deftest placeholder-count-by-kind
  (let [segs [{:text "[Music]"} {:text "(speaking foreign language)"} {:text "hi"}]]
    (is (= 2 (h/placeholder-count segs)))
    (is (= 1 (h/placeholder-count segs :foreign-speech)))
    (is (= 1 (h/placeholder-count segs :sound)))))

(deftest the-hygiene-version-names-the-placeholder-rule
  (is (re-find #"placeholder" h/version)
      "the rule is part of the transcript cache identity"))

(deftest collapse-runs-three-repeated
  (is (= [{:start 0 :end 3 :text "Thank you." :asr/loop-collapsed 3}
          {:start 3 :end 4 :text "Bye"}]
         (h/collapse-runs [{:start 0 :end 1 :text "Thank you."}
                           {:start 1 :end 2 :text "thank you"}
                           {:start 2 :end 3 :text "Thank you!"}
                           {:start 3 :end 4 :text "Bye"}]
                          {}))
      "three segments with same normalized text collapse into one, trailing segment unchanged"))

(deftest collapse-runs-short-runs-pass-through
  (is (= [{:start 0 :end 1 :text "a"} {:start 1 :end 2 :text "a"}]
         (h/collapse-runs [{:start 0 :end 1 :text "a"} {:start 1 :end 2 :text "a"}] {}))
      "two equal segments (fewer than min-repeats=3) pass through unchanged"))

(deftest collapse-runs-empty-input
  (is (= [] (h/collapse-runs [] {}))
      "empty input returns empty vector"))

(deftest collapse-runs-blank-segments
  (is (= [{:start 0 :end 1 :text ""} {:start 1 :end 2 :text ""} {:start 2 :end 3 :text ""}]
         (h/collapse-runs [{:start 0 :end 1 :text ""}
                           {:start 1 :end 2 :text ""}
                           {:start 2 :end 3 :text ""}] {}))
      "blank segments never form runs, each passes through unchanged"))

(deftest collapse-inner-repetition-high-compression
  (is (= {:start 10.0 :end 34.0 :text "the tide came in" :compression_ratio 7.23 :asr/loop-collapsed 3}
         (h/collapse-inner-repetition
          {:start 10.0 :end 34.0 :text "the tide came in the tide came in the tide came in the tide came"
           :compression_ratio 7.23}
          {}))
      "high compression ratio triggers inner collapse: 4 tokens repeated 3 times -> 'the tide came in'"))

(deftest collapse-inner-repetition-below-threshold
  (is (= {:start 10.0 :end 34.0 :text "the tide came in the tide came in the tide came in the tide came"
          :compression_ratio 1.8}
         (h/collapse-inner-repetition
          {:start 10.0 :end 34.0 :text "the tide came in the tide came in the tide came in the tide came"
           :compression_ratio 1.8}
          {}))
      "low compression ratio leaves segment unchanged"))

(deftest collapse-inner-repetition-noise-words
  (let [result (h/collapse-inner-repetition
                {:text "no no no no" :compression_ratio 3.0}
                {})]
    (is (= "no" (:text result)) "collapsed to single word")
    (is (= 4 (:asr/loop-collapsed result)) "loop count is 4")))

(deftest clean-combines-both-pass
  (let [loop-seg {:start 10.0 :end 34.0 :text "the tide came in the tide came in the tide came in the tide came"
                  :compression_ratio 7.23}
        result (h/clean [loop-seg
                         {:start 34.0 :end 35.0 :text "ok"}
                         {:start 35.0 :end 36.0 :text "OK"}
                         {:start 36.0 :end 37.0 :text "ok!"}]
                        {})]
    (is (= 2 (count result)) "two segments remain after both collapses")
    (is (= "the tide came in" (:text (first result))) "loop segment collapsed")
    (is (= 3 (:asr/loop-collapsed (first result))) "loop count on first segment")
    (is (= 34.0 (:start (second result))) "ok run starts at 34.0")
    (is (= 37.0 (:end (second result))) "ok run ends at 37.0")
    (is (= 3 (:asr/loop-collapsed (second result))) "ok run also has loop count")))

;; --- generator for property tests ---

(def gen-segments
  (gen/fmap
   (fn [parts]
     (first
      (reduce (fn [[acc start] [len text ratio]]
                [(conj acc {:start start :end (+ start len) :text text :compression_ratio ratio})
                 (+ start len)])
              [[] 0]
              parts)))
   (gen/vector (gen/tuple (gen/choose 1 3)
                          (gen/elements ["hi" "Hi!" "thank you" "Thank you." "go go go go" ""
                                         "the tide came in the tide came in the tide came in"])
                          (gen/double* {:min 0.5 :max 8.0 :NaN? false :infinite? false}))
               0 20)))

;; --- trifecta: golden + property + mutations ---

(deftrifecta clean-trifecta h/clean
  {:golden-path "test/golden/asr-hygiene-clean.edn"
   :cases {:repeated-phrase-loop [[{:start 10.0 :end 34.0 :text "the tide came in the tide came in the tide came in the tide came" :compression_ratio 7.23}] {}]
           :thanks   [[{:start 0 :end 1 :text "Thank you."} {:start 1 :end 2 :text "thank you"} {:start 2 :end 3 :text "thank you!"}] {}]
           :normal   [[{:start 0 :end 2 :text "hello there"} {:start 2 :end 4 :text "how are you"}] {}]
           :empty    [[] {}]}
   :apply?    true
   :gen       (gen/tuple gen-segments (gen/return {}))
   :pred      (fn [out] (and (every? #(<= (:start %) (:end %)) out)
                             (apply <= (or (seq (map :start out)) [0]))))
   :num-tests 100
   :mutations [["deletes looped segments instead of collapsing"
                (fn [segs _opts] (vec (remove #(> (or (:compression_ratio %) 0) 2.4) segs)))]
               ["never collapses" (fn [segs _opts] (vec segs))]]})

;; --- property tests ---

(defn- merged-intervals [segs]
  (reduce (fn [acc [s e]]
            (if-let [[ps pe] (peek acc)]
              (if (<= s pe) (conj (pop acc) [ps (max pe e)]) (conj acc [s e]))
              [[s e]]))
          []
          (sort (map (juxt :start :end) segs))))

(defspec clean-keeps-coverage 100
  (prop/for-all [s gen-segments]
    (= (merged-intervals s) (merged-intervals (h/clean s {})))))

(defspec clean-is-idempotent 100
  (prop/for-all [s gen-segments]
    (= (h/clean (h/clean s {}) {}) (h/clean s {}))))

(defspec clean-never-grows 100
  (prop/for-all [s gen-segments]
    (<= (count (h/clean s {})) (count s))))
