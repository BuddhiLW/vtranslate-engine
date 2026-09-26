(ns vtranslate.engine.calc.reflow.optimal-test
  "What the whole-track solver buys over the rule table, one behaviour each.
   The contract it shares with every strategy is in contract-test."
  (:require [clojure.test :refer [deftest is testing]]
            [vtranslate.engine.calc.reflow :as reflow]
            [vtranslate.engine.calc.reflow.optimal :as sut]
            [hive-test.mutation :refer [deftest-mutations]]))

(defn- cue [s e & lines] {:index 0 :start-ms s :end-ms e :lines (vec lines)})

(defn- optimal [cues & {:as rules}]
  (reflow/reflow cues (assoc rules :strategy :optimal)))

(deftest a-short-blank-between-two-phrases-is-closed
  (let [cues [(cue 0 1000 "Hello there.") (cue 1300 2500 "How are you?")]
        [a b] (optimal cues)]
    (testing "the first caption holds until the next, less the minimum gap"
      (is (= 1220 (:end-ms a)))
      (is (= 1300 (:start-ms b))))
    (testing "two sentences stay two captions"
      (is (= ["Hello there."] (:lines a)))
      (is (= ["How are you?"] (:lines b))))
    (testing "the rule table leaves the 300 ms flicker"
      (is (= 1000 (:end-ms (first (reflow/reflow cues {:min-gap-ms 80 :min-dur-ms 800}))))))))

(deftest a-long-silence-is-left-blank
  (let [[a] (optimal [(cue 0 1500 "Hello there.") (cue 9000 10000 "Much later.")])]
    (is (= 1500 (:end-ms a)) "nothing to read, nothing to close: the speech's own end")))

(deftest fragments-of-one-sentence-become-one-caption
  (let [out (optimal [(cue 0 400 "I think") (cue 450 800 "that we") (cue 850 1300 "should go.")])]
    (is (= 1 (count out)))
    (is (= ["I think that we should go."] (:lines (first out))))
    (is (= 0 (:start-ms (first out))))
    (is (<= 1300 (:end-ms (first out))))))

(deftest a-merge-never-spans-a-real-pause
  (let [out (optimal [(cue 0 400 "I think") (cue 1500 1900 "so.")])]
    (is (= 2 (count out)))))

(deftest a-caption-too-brief-to-read-is-held-longer
  (let [[a] (optimal [(cue 0 300 "Absolutely not, never.") (cue 5000 6000 "Fine.")])]
    (is (>= (- (:end-ms a) (:start-ms a)) 800))
    (is (<= (:end-ms a) (- 5000 80)))))

(deftest a-held-caption-never-runs-into-the-next
  (let [[a b] (optimal [(cue 0 300 "Absolutely not, never.") (cue 700 1700 "Fine.")])]
    (is (= 620 (:end-ms a)) "held as long as the next cue allows, and no longer")
    (is (= 700 (:start-ms b))))
  (testing "close enough to share a caption, that is cheaper than an unreadable flash"
    (is (= [["Absolutely not, never. Fine."]]
           (map :lines (optimal [(cue 0 300 "Absolutely not, never.") (cue 500 1500 "Fine.")]))))))

(deftest starts-move-only-when-a-lead-in-is-allowed
  (let [cues [(cue 0 1000 "Before.")
              (cue 3000 3400 "This one is dense, far too dense to read.")
              (cue 3480 5000 "After.")]]
    (is (= 3000 (:start-ms (second (optimal cues)))))
    (let [[_ b _] (optimal cues :max-lead-ms 200 :max-group 1)]
      (is (= 2800 (:start-ms b)) "earlier by the lead, to buy reading time"))))

(deftest two-speakers-keep-their-lines
  (let [out (optimal [(cue 0 2000 "- Are you coming?" "- In a minute.")])]
    (is (= ["- Are you coming?" "- In a minute."] (:lines (first out))))))

(deftest lines-break-where-the-language-allows
  (let [[a] (optimal [(cue 0 4000 "Eu fui para a feira comprar frutas e legumes frescos hoje cedo")]
                     :language "pt-BR")]
    (is (= 2 (count (:lines a))))
    (is (every? #(<= (count %) 42) (:lines a)))
    (is (not (re-find #"(?i)\b(a|e|de|para)$" (first (:lines a)))))))

(deftest roll-up-carries-the-last-line-over-a-close-cut
  (let [cues [(cue 0 2000 "First sentence here.") (cue 2080 4000 "Second one.")
              (cue 9000 10000 "After a pause.")]
        [a b c] (optimal cues :style :roll-up)]
    (is (= ["First sentence here."] (:lines a)))
    (is (= ["First sentence here." "Second one."] (:lines b)))
    (is (= ["After a pause."] (:lines c)) "not across a pause")
    (is (= ["Second one."] (:lines (second (optimal cues)))) "pop-on is the default")))

(deftest limits-and-weights-are-data
  (let [cues [(cue 0 400 "I think") (cue 450 800 "that we") (cue 850 1300 "should go.")]]
    (testing "a caller who prices merging out of reach keeps three captions"
      (is (= 3 (count (optimal cues :weights {:merge 1000.0})))))
    (testing "or forbids it outright"
      (is (= 3 (count (optimal cues :max-group 1)))))))

(deftest the-solver-reports-what-its-track-costs
  (let [cues [(cue 0 400 "I think") (cue 450 800 "that we") (cue 850 1300 "should go.")]
        free (sut/solve cues {})
        tied (sut/solve cues {:max-group 1})]
    (is (= 1 (count (:cues free))))
    (is (= 3 (count (:cues tied))))
    (is (< (:cost free) (:cost tied)) "taking choices away can only cost more")))

;; MUTATION: break what the behaviours rest on, prove the assertions notice.

(deftest-mutations pricing-the-blank-is-load-bearing
  vtranslate.engine.calc.reflow.optimal/edge-cost
  [["blank-is-free"   (fn [e s _] (when-not (neg? (- s e)) 0.0))]
   ["overlap-allowed" (fn [_ _ _] 0.0)]]
  (fn []
    (let [[a b] (optimal [(cue 0 1000 "Hello there.") (cue 1300 2500 "How are you?")])]
      (is (= 1220 (:end-ms a)))
      (is (<= (:end-ms a) (:start-ms b))))
    (let [[a b] (optimal [(cue 0 300 "Absolutely not, never.") (cue 700 1700 "Fine.")])]
      (is (= 620 (:end-ms a)))
      (is (= 700 (:start-ms b))))))

(deftest-mutations grouping-is-load-bearing
  vtranslate.engine.calc.reflow.optimal/group
  [["never-merge" (let [real @#'sut/group] (fn [cues p] (when (= 1 (count cues)) (real cues p))))]
   ["drop-words"  (let [real @#'sut/group] (fn [cues p] (some-> (real cues p) (assoc :lines ["x"]))))]]
  (fn []
    (let [out (optimal [(cue 0 400 "I think") (cue 450 800 "that we") (cue 850 1300 "should go.")])]
      (is (= [["I think that we should go."]] (map :lines out))))))
