(ns vtranslate.engine.adapters.onnx-trifecta-test
  (:require [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [vtranslate.engine.schema.port-values :as p]
            [vtranslate.engine.adapters.transcriber.onnx-bytedeco :as o]
            [vtranslate.engine.port.transcriber :as asr]))
(deftest offline-onnx-port
  (let [seen (atom nil) fixture {:ok {:segments [{:start-ms 0 :end-ms 100 :text "fixture" :confidence 1.0}]}}]
    (with-redefs [clojure.core/requiring-resolve
                  (fn [_] (fn [dir path language opts] (reset! seen [dir path language opts]) fixture))]
      (let [out (asr/transcribe (o/->OnnxBytedecoTranscriber "model" {:beam-size 1}) "fixture.wav" "he" {:beam-size 2})]
        (is (m/validate p/TranscribeResult out)) (is (= fixture out))
        (is (= ["model" "fixture.wav" "he" {:beam-size 2}] @seen))))))
