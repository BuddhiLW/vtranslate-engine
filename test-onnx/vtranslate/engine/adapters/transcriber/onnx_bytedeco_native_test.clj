(ns vtranslate.engine.adapters.transcriber.onnx-bytedeco-native-test
  (:require [clojure.test :refer [deftest is]]
            [vtranslate.engine.adapters.transcriber.onnx-bytedeco :as adapter]
            [vtranslate.engine.adapters.transcriber.onnx-bytedeco-native :as sut]
            [vtranslate.engine.contract.ports-contract :as contract]))

(deftest tensor-constructors-preserve-shape
  (let [tensor (sut/float-tensor (float-array [1.0 2.0 3.0 4.0]) [1 2 2])]
    (is (= [1 2 2] (sut/tensor-shape tensor)))
    (is (= [1.0 2.0 3.0 4.0]
           (mapv double (sut/tensor->floats tensor)))))
  (is (thrown? clojure.lang.ExceptionInfo
               (sut/long-tensor [1 2 3] [1 2]))))

(deftest silence-produces-a-finite-whisper-mel
  (let [mel (sut/log-mel-spectrogram (float-array 1600))]
    (is (= (* 80 3000) (alength mel)))
    (is (every? #(Double/isFinite (double %)) mel))
    (is (< (Math/abs (- -1.5 (double (aget mel 0)))) 1.0e-6))))

(deftest byte-bpe-and-timestamps-become-segments
  (let [tokenizer {:id->token {10 "<|0.00|>"
                               11 "ĠHello"
                               12 "<|1.00|>"
                               13 "Ġworld"
                               14 "<|2.50|>"}
                   :special {"<|0.00|>" 10
                             "<|1.00|>" 12
                             "<|2.50|>" 14}}]
    (is (= "Hello world" (sut/detokenize tokenizer [11 13])))
    (is (= [{:start 0.0 :end 1.0 :text "Hello"}
            {:start 1.0 :end 2.5 :text "world"}]
           (sut/timestamp-segments tokenizer [10 11 12 13 14] 3.0)))))

(deftest ^:integration real-whisper-export-transcribes-when-configured
  (let [model-dir (System/getenv "VT_ONNX_MODEL_DIR")
        wav (System/getenv "VT_ONNX_WAV")]
    (if (and (seq model-dir) (seq wav))
      (let [result (sut/transcribe-wav model-dir wav "en" {:max-new-tokens 64})]
        (is (contains? result :ok) (pr-str result))
        (is (seq (get-in result [:ok :segments])) (pr-str result))
        (is (every? (comp seq :text) (get-in result [:ok :segments])))
        (contract/check-transcriber
         (adapter/->OnnxBytedecoTranscriber model-dir {:max-new-tokens 64})
         {:path wav}
         "en"))
      (is true "set VT_ONNX_MODEL_DIR and VT_ONNX_WAV for real-graph smoke"))))
