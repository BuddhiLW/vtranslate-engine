(ns vtranslate.engine.calc.reflow-trifecta-test
  "Named regressions and generated contracts for every public reflow callable.
   Generators bound track size, not text to a single happy-path fixture."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.schema.calc-reflow :as s]
            [vtranslate.engine.calc.reflow :as r]
            [vtranslate.engine.calc.reflow.linebreak :as lb]
            [vtranslate.engine.calc.reflow.optimal :as opt]))

(def word-gen (gen/elements ["hello" "world" "the" "café" "字幕" "yes!" "extraordinary"]))
(def lines-gen (gen/vector (gen/fmap #(str/join " " %) (gen/vector word-gen 0 6)) 0 3))
(def cues-gen
  (gen/fmap (fn [lines]
              (mapv (fn [i ls] {:index (+ 7 i) :start-ms (* i 2000)
                               :end-ms (+ 1000 (* i 2000)) :lines ls})
                    (range) lines))
            (gen/vector lines-gen 0 5)))
(def sample [{:index 9 :start-ms 0 :end-ms 1000 :lines ["hello world"]}
             {:index 4 :start-ms 900 :end-ms 1800 :lines ["the café"]}])
(def rules-gen
  (gen/one-of [(gen/return {})
               (gen/hash-map :strategy (gen/elements [:greedy :optimal])
                             :max-chars-line (gen/choose 5 42)
                             :max-lines (gen/choose 1 3))]))

(deftrifecta words r/words
  {:golden-path "test/golden/calc-reflow-words.edn" :apply? true
   :cases {:empty [[]] :whitespace [["  hello\tworld " "" " café "]]}
   :gen (gen/tuple lines-gen)
   :pred #(and (m/validate s/Lines %) (every? (complement str/blank?) %))})

(deftrifecta drop-music r/drop-music
  {:golden-path "test/golden/calc-reflow-drop-music.edn" :apply? true
   :cases {:empty [[]] :speech [sample]
           :markers [[{:index 1 :start-ms 0 :end-ms 1 :lines ["[music]"]}
                      {:index 2 :start-ms 2 :end-ms 3 :lines ["♪ song ♪"]}]]}
   :gen (gen/tuple cues-gen) :pred #(m/validate s/Cues %)
   :mutations [["drops-speech" (constantly [])]]})

(defn- ordered? [cs]
  (every? (fn [[a b]] (<= (:end-ms a) (:start-ms b))) (partition 2 1 cs)))
(deftrifecta merge-overlaps r/merge-overlaps
  {:golden-path "test/golden/calc-reflow-merge-overlaps.edn" :apply? true
   :cases {:empty [[]] :overlap [sample] :reversed [(vec (reverse sample))]
           :touching [[(first sample) (assoc (second sample) :start-ms 1000)]]}
   :gen (gen/tuple (gen/fmap #(vec (reverse %)) cues-gen))
   :pred #(and (m/validate s/Cues %) (ordered? %))
   :mutations [["no-merge" identity]]})

(deftrifecta split-cues r/split-cues
  {:golden-path "test/golden/calc-reflow-split-cues.edn" :apply? true
   :cases {:empty [17 42 2 []] :dense [2 8 1 sample]
           :zero-duration [17 nil nil [(assoc (first sample) :end-ms 0)]]}
   :gen (gen/tuple (gen/choose 1 30) (gen/choose 5 42) (gen/choose 1 3) cues-gen)
   :pred #(m/validate s/Cues %)})

(deftrifecta snap-cues r/snap-cues
  {:golden-path "test/golden/calc-reflow-snap-cues.edn" :apply? true
   :cases {:empty [40 []] :round [40 sample] :collapsed [100 [(assoc (first sample) :end-ms 1)]]}
   :gen (gen/tuple (gen/choose 1 100) cues-gen)
   :pred #(and (m/validate s/Cues %) (every? (fn [c] (< (:start-ms c) (:end-ms c))) %))})

(deftrifecta reindex r/reindex
  {:golden-path "test/golden/calc-reflow-reindex.edn" :apply? true
   :cases {:empty [[]] :unsorted [(vec (reverse sample))]}
   :gen (gen/tuple (gen/fmap #(vec (reverse %)) cues-gen))
   :pred #(and (m/validate s/Cues %) (= (map :index %) (range 1 (inc (count %)))))
   :mutations [["keeps-indices" identity]]})

(deftrifecta shape r/shape
  {:golden-path "test/golden/calc-reflow-shape.edn" :apply? true
   :cases {:empty [[] {}] :greedy [sample {}] :optimal [sample {:strategy :optimal}]
           :roll-up [sample {:strategy :optimal :style :roll-up}]}
   :gen (gen/tuple cues-gen rules-gen) :pred #(m/validate s/Cues %)})

(deftrifecta reflow r/reflow
  {:golden-path "test/golden/calc-reflow-reflow.edn" :apply? true
   :cases {:default [sample {}] :optimal [sample {:strategy :optimal}]
           :all-rules [sample {:drop-music? true :max-dur-ms 900 :min-dur-ms 500
                               :min-gap-ms 80 :max-chars-line 8 :max-lines 2 :max-cps 5 :snap 40}]}
   :gen (gen/tuple cues-gen rules-gen) :pred #(m/validate s/Cues %)})

(deftrifecta try-reflow r/try-reflow
  {:golden-path "test/golden/calc-reflow-try-reflow.edn" :apply? true
   :cases {:known [sample {}] :unknown [sample {:strategy :missing}]}
   :gen (gen/tuple cues-gen (gen/one-of [rules-gen (gen/return {:strategy :missing})]))
   :pred #(m/validate s/Result %)})

;; Registry introspection is deterministic only while no extensions are loaded.
(deftrifecta strategies r/strategies
  {:golden-path "test/golden/calc-reflow-strategies.edn" :apply? true
   :cases {:registered []} :gen (gen/return [])
   :pred #(and (m/validate s/Strategies %) (contains? % :greedy) (contains? % :optimal))})

(deftrifecta weak-set lb/weak-set
  {:golden-path "test/golden/calc-reflow-weak-set.edn" :apply? true
   :cases {:absent [nil] :regional ["PT_br"] :unknown ["zz"]}
   :gen (gen/tuple (gen/elements [nil "en" "pt-BR" "fr" "de" "es" "it" "zz"]))
   :pred #(and (m/validate s/WeakWords %) (seq %))})

(deftrifecta break-lines lb/break-lines
  {:golden-path "test/golden/calc-reflow-break-lines.edn" :apply? true
   :cases {:empty [[] 42 {}] :balanced [["hello" "world" "the" "café"] 12 {}]
           :hard-split [["extraordinary"] 3 {:max-lines 1}]
           :blank [["" " "] 8 {}]}
   :gen (gen/tuple (gen/vector word-gen 0 10) (gen/choose 1 42)
                   (gen/hash-map :max-lines (gen/choose 1 3)))
   :pred #(m/validate s/Breaks %)})

(deftrifecta solve opt/solve
  {:golden-path "test/golden/calc-reflow-solve.edn" :apply? true
   :cases {:empty [[] {}] :one [[(first sample)] {}]
           :sequential [[(first sample) (assoc (second sample) :start-ms 1100)] {}]}
   :gen (gen/tuple cues-gen rules-gen)
   :pred #(and (m/validate s/Solution %) (ordered? (:cues %)))})

(deftest public-surface-is-accounted-for
  (is (= '#{words drop-music merge-overlaps split-cues snap-cues reindex shape strategies reflow try-reflow}
         (set (keys (ns-publics 'vtranslate.engine.calc.reflow)))))
  (is (= '#{weak-set break-lines}
         (set (for [[k v] (ns-publics 'vtranslate.engine.calc.reflow.linebreak)
                    :when (:arglists (meta v))] k))))
  (is (= '#{solve}
         (set (for [[k v] (ns-publics 'vtranslate.engine.calc.reflow.optimal)
                    :when (:arglists (meta v))] k)))))
