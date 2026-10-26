(ns vtranslate.engine.calc.word-cues-trifecta-test
  (:require [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.schema.calc-text :as s]
            [vtranslate.engine.calc.word-cues :as sut]))
(def segment {:start 0 :end 30 :text "coarse" :confidence 0.9})
(def words [{:start 0 :end 1 :word " Hello."} {:start 2 :end 3 :word " world"}])
(deftrifecta recut sut/recut
  {:golden-path "test/golden/calc-text-word-cues-recut.edn" :apply? true
   :cases {:no-segments [[] words] :no-words [[segment] []]
           :sentence [[segment] words]
           :length [[segment] words {:max-chars 5}]
           :duration [[segment] words {:max-ms 1500}]
           :no-owner [[segment {:start 30 :end 40 :text "untouched"}] words]
           :invalid-words [[segment] [{:start nil :end 1 :word "a"} {:start 0 :end 1 :word " "}]]}
   :gen (gen/bind (gen/vector (gen/tuple (gen/choose 1 500) (gen/choose 0 1000)
                                         (gen/not-empty gen/string-alphanumeric)) 1 20)
                  (fn [pieces]
                    (let [ws (:words
                              (reduce (fn [{:keys [at words]} [duration gap text]]
                                        (let [start (+ at gap) end (+ start duration)]
                                          {:at end :words (conj words {:start (/ start 1000.0) :end (/ end 1000.0) :word (str " " text)})}))
                                      {:at 0 :words []} pieces))]
                      (gen/tuple (gen/return [segment]) (gen/return ws)
                                 (gen/hash-map :max-ms (gen/choose 1000 7000) :max-chars (gen/choose 10 84)
                                               :pause-ms (gen/choose 100 800) :min-ms (gen/choose 0 1000))))))
   :pred #(and (m/validate [:vector s/SegmentSeconds] %)
               (every? (fn [piece] (and (<= (:start piece) (:end piece)) (= 0.9 (:confidence piece)))) %)
               (apply <= (map :start %)))
   :mutations [["ignore-words" (fn [segments & _] (vec segments))]]})
