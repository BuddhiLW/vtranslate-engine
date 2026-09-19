(ns vtranslate.engine.calc.rendering-reflow-test
  "A video job's translated units shaped by reflow rules on their way into the
   subtitle track. Without rules the promoter is what it always was."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-dsl.result :as r]
            [vtranslate.engine.shared :as shared]
            [vtranslate.engine.domain.translation :as t]
            [vtranslate.engine.calc.rendering :as sut]))

(defn- ->tcues [target-lang units]
  (let [tc (:ok (t/make-translated-cues {:id "tc-1" :transcript-id "tr-1"
                                         :source-language "en"
                                         :target-language target-lang}))]
    (:ok (t/complete
          (reduce t/add-unit (t/begin tc)
                  (map (fn [[s e text]]
                         (:ok (t/make-translation-unit
                               {:start-ms s :end-ms e :source-language "en"
                                :source-text "src" :target-text text})))
                       units))))))

(defn- cues [res]
  (mapv (fn [c] (let [[s e] (shared/range-ms (:range c))]
                  {:index (:index c) :start-ms s :end-ms e :lines (:lines c)}))
        (get-in res [:ok :cues])))

(def ^:private spec {:id "sub-1" :format :format/srt})

(def ^:private fragments
  [[0 400 "Eu acho"] [450 800 "que a gente"] [850 1300 "devia ir."]
   [1600 5600 "Eu fui para a feira comprar frutas e legumes frescos hoje cedo"]])

(deftest without-rules-every-unit-is-one-cue-with-one-line
  (let [out (cues (sut/build-subtitle-track (->tcues "pt-BR" fragments) spec))]
    (is (= 4 (count out)))
    (is (every? #(= 1 (count (:lines %))) out))))

(deftest the-optimal-strategy-shapes-the-whole-track
  (let [res (sut/build-subtitle-track (->tcues "pt-BR" fragments)
                                      (assoc spec :reflow {:strategy :optimal}))
        [a b :as out] (cues res)]
    (is (r/ok? res))
    (is (= [1 2] (map :index out)))
    (testing "the fragments of one sentence are one caption"
      (is (= ["Eu acho que a gente devia ir."] (:lines a))))
    (testing "held until the next, not blinking off for 300 ms"
      (is (= (- 1600 80) (:end-ms a))))
    (testing "broken in two for the track's own language, not after a preposition"
      (is (= 2 (count (:lines b))))
      (is (every? #(<= (count %) 42) (:lines b)))
      (is (not (re-find #"(?i)\b(a|e|de|para)$" (first (:lines b))))))))

(deftest the-greedy-strategy-is-as-available-here
  (let [out (cues (sut/build-subtitle-track
                   (->tcues "pt-BR" fragments)
                   (assoc spec :reflow {:strategy :greedy :max-chars-line 42})))]
    (is (= 4 (count out)) "the rule table merges nothing")
    (is (every? #(<= (count %) 42) (mapcat :lines out)))))

(deftest a-strategy-nobody-registered-fails-the-render-and-says-which-exist
  (let [res (sut/build-subtitle-track (->tcues "pt-BR" fragments)
                                      (assoc spec :reflow {:strategy :psychic}))]
    (is (r/err? res))
    (is (= :error/render-failed (:error res)))
    (is (re-find #"psychic.*greedy.*optimal" (str (:reason res))))))
