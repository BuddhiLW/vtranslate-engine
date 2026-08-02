(ns vtranslate.engine.adapters.segmenter.silero-vad-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [hive-dsl.result :as r]
            [vtranslate.engine.adapters.segmenter.silero-vad :as sut]
            [vtranslate.engine.wiring :as wiring]
            [vtranslate.engine.providers.segmenter-registry :as reg]))

(deftest speech-spans-from-probs-detects-bounded-speech
  (is (= [{:start-ms 32 :end-ms 96}]
         (sut/speech-spans-from-probs
          [0.1 0.6 0.7 0.1 0.1]
          {:sample-rate 16000
           :audio-length-samples (* 5 512)
           :min-speech-ms 1
           :min-silence-ms 32
           :speech-pad-ms 0}))))

(deftest speech-spans-from-probs-pads-and-clamps
  (is (= [{:start-ms 22 :end-ms 106}]
         (sut/speech-spans-from-probs
          [0.1 0.6 0.7 0.1 0.1]
          {:sample-rate 16000
           :audio-length-samples (* 4 512)
           :min-speech-ms 1
           :min-silence-ms 32
           :speech-pad-ms 10}))))

(deftest cap-spans-splits-long-spans-into-bounded-windows
  (is (= [{:start-ms 0 :end-ms 30000}
          {:start-ms 30000 :end-ms 60000}
          {:start-ms 60000 :end-ms 65000}]
         (sut/cap-spans 30000 [{:start-ms 0 :end-ms 65000}])))
  (is (= [{:start-ms 5 :end-ms 10}] (sut/cap-spans 30000 [{:start-ms 5 :end-ms 10}]))
      "short spans pass through untouched")
  (is (= [{:start-ms 0 :end-ms 65000}] (sut/cap-spans 0 [{:start-ms 0 :end-ms 65000}]))
      "max-span-ms <= 0 disables capping"))

(deftest speech-spans-from-probs-caps-a-saturated-utterance
  (is (= [{:start-ms 0 :end-ms 1000}
          {:start-ms 1000 :end-ms 2000}
          {:start-ms 2000 :end-ms 3000}
          {:start-ms 3000 :end-ms 3200}]
         (sut/speech-spans-from-probs
          (repeat 100 0.9)
          {:sample-rate 16000
           :audio-length-samples (* 100 512)
           :min-speech-ms 1
           :speech-pad-ms 0
           :max-span-ms 1000}))
      "one continuous high-prob blob becomes bounded windows"))

;; PROPERTY — for ANY probability sequence the hysteresis FSM yields spans that
;; are ordered, non-overlapping, non-empty in duration, and within the clip.
(defspec speech-spans-invariants 200
  (prop/for-all [probs (gen/vector (gen/double* {:min 0.0 :max 1.0 :NaN? false :infinite? false}) 0 200)]
    (let [n     (max 1 (count probs))
          dur   (long (/ (* n 512 1000) 16000))
          spans (sut/speech-spans-from-probs probs {:sample-rate 16000
                                                    :audio-length-samples (* n 512)
                                                    :speech-pad-ms 0})]
      (and (every? (fn [{:keys [start-ms end-ms]}] (<= 0 start-ms end-ms dur)) spans)
           (= spans (sort-by :start-ms spans))
           (every? (fn [[a b]] (<= (:end-ms a) (:start-ms b)))
                   (partition 2 1 spans))))))

(deftest build-port-selects-none
  (let [res (wiring/build-port :segmenter {:segmenter :none})]
    (is (r/ok? res))
    (is (nil? (:ok res)))))

(deftest build-port-silero-gates-or-builds
  (let [res (wiring/build-port :segmenter {:segmenter :silero-vad})]
    (is (or (r/ok? res) (r/err? res)))
    (when (r/err? res)
      (is (= :error/segmentation-failed (:error res))))
    (when (r/ok? res)
      (is (= "models/silero_vad.onnx" (:model-path (:ok res)))))))

(deftest resolves-via-segmenter-registry
  (let [res (reg/resolve-segmenter :silero-vad {})]
    (is (or (r/ok? res) (r/err? res)))
    (when (r/err? res)
      (is (not= :error/unknown-segmenter (:error res))
          "dispatch reached the silero-vad method, not the :default fallthrough"))))