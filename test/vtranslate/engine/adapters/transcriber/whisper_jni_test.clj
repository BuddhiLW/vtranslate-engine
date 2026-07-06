(ns vtranslate.engine.adapters.transcriber.whisper-jni-test
  (:require [clojure.test :refer [deftest is]]
            [hive-dsl.result :as r]
            [vtranslate.engine.adapters.transcriber.whisper-jni :as sut]))

(deftest transcribe-with-spans-slices-and-offsets
  (let [seen (atom [])
        fake (fn [_model-path _use-gpu? samples _language]
               (swap! seen conj (alength ^floats samples))
               (r/ok [{:start-ms 10 :end-ms 20 :text "x"}]))
        samples (float-array 48000)
        res (#'sut/transcribe-with-spans fake "model.bin" false samples 16000 "en"
                                         [{:start-ms 1000 :end-ms 1500}
                                          {:start-ms 2500 :end-ms 3000}] 0)]
    (is (r/ok? res))
    (is (= [8000 8000] @seen))
    (is (= [{:start-ms 1010 :end-ms 1020 :text "x"}
            {:start-ms 2510 :end-ms 2520 :text "x"}]
           (:ok res)))))

(deftest transcribe-with-spans-falls-back-to-whole-clip
  (let [seen (atom nil)
        fake (fn [_model-path _use-gpu? samples _language]
               (reset! seen (alength ^floats samples))
               (r/ok [{:start-ms 0 :end-ms 10 :text "whole"}]))
        samples (float-array 1234)
        res (#'sut/transcribe-with-spans fake "model.bin" false samples 16000 nil nil 0)]
    (is (r/ok? res))
    (is (= 1234 @seen))
    (is (= [{:start-ms 0 :end-ms 10 :text "whole"}] (:ok res)))))
