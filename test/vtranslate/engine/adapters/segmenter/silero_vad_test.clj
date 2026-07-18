(ns vtranslate.engine.adapters.segmenter.silero-vad-test
  (:require [clojure.test :refer [deftest is]]
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