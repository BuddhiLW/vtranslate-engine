(ns vtranslate.engine.calc.reflow.contract-test
  "The contract every reflow strategy honours, run against every strategy that
   is registered. A new strategy is held to it by being loaded; nothing here
   names one. This is what lets `:strategy` be swapped without the caller
   noticing anything but better captions."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [vtranslate.engine.calc.reflow :as reflow]
            ;; loaded for its defmethod
            [vtranslate.engine.calc.reflow.optimal]))

(def ^:private gen-cue
  (gen/fmap (fn [[s d ws]]
              {:index 0 :start-ms s :end-ms (+ s d)
               :lines [(str/join " " (map #(str "w" %) ws))]})
            (gen/tuple (gen/choose 0 100000)
                       (gen/choose 1 8000)
                       (gen/vector (gen/choose 1 999) 1 12))))

(def ^:private rules
  {:drop-music? false :min-gap-ms 40 :min-dur-ms 500 :max-dur-ms 6000
   :max-chars-line 42 :max-lines 2 :max-cps 18})

(defn- words-of [cues]
  (mapcat #(str/split (str/trim %) #"\s+") (mapcat :lines cues)))

(defn- honours-the-contract? [cues out]
  (and (vector? out)
       (every? (fn [c] (and (<= (:start-ms c) (:end-ms c))
                            (seq (:lines c))
                            (every? #(and (string? %) (seq %) (<= (count %) 42)) (:lines c))))
               out)
       (every? (fn [[a b]] (<= (:end-ms a) (:start-ms b))) (partition 2 1 out))
       (= (map :index out) (range 1 (inc (count out))))
       ;; every word once, in time order
       (= (words-of (sort-by (juxt :start-ms :end-ms) cues)) (words-of out))))

(deftest every-strategy-honours-the-contract
  (is (contains? (reflow/strategies) :greedy))
  (is (contains? (reflow/strategies) :optimal))
  (doseq [strategy (reflow/strategies)]
    (testing (str strategy)
      (let [result (tc/quick-check
                    200
                    (prop/for-all [cues (gen/vector gen-cue 0 10)]
                      (honours-the-contract?
                       cues (reflow/reflow cues (assoc rules :strategy strategy)))))]
        (is (:pass? result) (pr-str (:shrunk result))))
      (is (= [] (reflow/reflow [] (assoc rules :strategy strategy)))))))

(deftest every-strategy-stays-total-under-snap
  (doseq [strategy (reflow/strategies)]
    (testing (str strategy)
      (let [result (tc/quick-check
                    100
                    (prop/for-all [cues (gen/vector gen-cue 0 10)
                                   snap (gen/choose 1 500)]
                      (let [out (reflow/reflow cues (assoc rules :strategy strategy :snap snap))]
                        (and (vector? out)
                             (every? #(< (:start-ms %) (:end-ms %)) out)
                             (apply <= 0 (map :start-ms out))))))]
        (is (:pass? result) (pr-str (:shrunk result)))))))

(deftest the-strategy-is-the-rules-to-name
  (let [cues [{:index 0 :start-ms 0 :end-ms 1000 :lines ["a"]}]]
    (testing "absent means greedy, the behaviour before strategies existed"
      (is (= (reflow/reflow cues {:strategy :greedy}) (reflow/reflow cues {}))))
    (testing "a string from a JSON spec names it as well as a keyword"
      (is (= (reflow/reflow cues {:strategy :optimal}) (reflow/reflow cues {:strategy "optimal"}))))
    (testing "one nobody registered is refused, with the ones that are"
      (let [e (try (reflow/reflow cues {:strategy :psychic}) nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e))
        (is (= (reflow/strategies) (:known (ex-data e))))))))
