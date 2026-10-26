(ns vtranslate.engine.adapters.silero-trifecta-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.adapters.segmenter.silero-vad :as vad]
            [vtranslate.engine.adapters.transcriber.support :as sup]
            [vtranslate.engine.schema.port-values :as p]
            [vtranslate.engine.port.segmenter :as seg]))
(def spans-gen (gen/fmap (fn [lengths] (loop [ls lengths at 0 out []]
                                       (if-let [n (first ls)]
                                         (recur (next ls) (+ at n 5) (conj out {:start-ms at :end-ms (+ at n)})) out)))
                        (gen/vector (gen/choose 1 1000) 0 12)))
(defn cap-observation [[cap spans :as in]] {:in in :out (vad/cap-spans cap spans)})
(deftrifecta cap-spans cap-observation
  {:golden-path "test/golden/adapters-silero-cap.edn"
   :cases {:empty [10 []] :split [10 [{:start-ms 5 :end-ms 26}]] :disabled [0 [{:start-ms 1 :end-ms 99}]]}
   :gen (gen/tuple (gen/choose 1 1000) spans-gen)
   :pred (fn [{[cap spans] :in out :out}]
           (and (m/validate p/Spans out)
                (= (reduce + 0 (map #(- (:end-ms %) (:start-ms %)) spans))
                   (reduce + 0 (map #(- (:end-ms %) (:start-ms %)) out)))
                (every? #(<= (- (:end-ms %) (:start-ms %)) cap) out)
                (every? (fn [s] (some #(<= (:start-ms %) (:start-ms s) (:end-ms s) (:end-ms %)) spans)) out)))})
(defn merge-observation [[limit spans :as in]] {:in in :out (vad/merge-spans limit spans)})
(deftrifecta merge-spans merge-observation
  {:golden-path "test/golden/adapters-silero-merge.edn"
   :cases {:merge [30 [{:start-ms 0 :end-ms 10} {:start-ms 20 :end-ms 30}]] :disabled [0 []]}
   :gen (gen/tuple (gen/choose 1 3000) spans-gen)
   :pred (fn [{[limit spans] :in out :out}]
           (and (m/validate p/Spans out) (<= (count out) (count spans))
                (every? (fn [s] (some #(<= (:start-ms %) (:start-ms s) (:end-ms s) (:end-ms %)) out)) spans)
                (= (mapv :start-ms out) (mapv :start-ms (filter #(some #{(:start-ms %)} (map :start-ms out)) spans)))
                (= out (vad/merge-spans limit out))))})
(defn probs-observation [probs]
  {:in probs :out (vad/speech-spans-from-probs probs {:sample-rate 16000 :audio-length-samples (* 512 (count probs))
                                                    :min-speech-ms 0 :min-silence-ms 32 :speech-pad-ms 0
                                                    :merge-to-ms 0 :max-span-ms 64})})
(deftrifecta speech-spans-from-probs probs-observation
  {:golden-path "test/golden/adapters-silero-probs.edn"
   :cases {:silence [0.0 0.0] :speech [1.0 1.0 1.0] :empty [] :hysteresis [0.9 0.4 0.1 0.1]}
   :gen (gen/vector (gen/elements [0.0 0.2 0.4 0.6 1.0]) 0 80)
   :pred (fn [{:keys [in out]}]
           (and (m/validate p/Spans out)
                (every? #(<= 0 (:start-ms %) (:end-ms %) (* 32 (count in))) out)
                (every? #(<= (- (:end-ms %) (:start-ms %)) 64) out)
                (if (every? #(< % 0.5) in) (empty? out) true)))})
(deftest offline-silero-port
  (with-redefs [sup/read-wav-mono-floats (fn [_] {:ok {:samples (float-array 1536) :sample-rate 16000}})
                clojure.core/requiring-resolve (fn [_] (atom (fn [& _] [1.0 1.0 1.0])))]
    (let [result (seg/segment (vad/->SileroVadSegmenter "fixture.onnx" 0.5 0.35 0 32 0 64 0) "fixture.wav" {})]
      (is (m/validate p/SegmentResult result))
      (is (= [{:start-ms 0 :end-ms 64} {:start-ms 64 :end-ms 96}] (get-in result [:ok :spans]))))))
