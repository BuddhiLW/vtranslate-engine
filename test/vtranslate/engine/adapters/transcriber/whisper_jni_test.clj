(ns vtranslate.engine.adapters.transcriber.whisper-jni-test
  (:require [clojure.test :refer [deftest is]]
            [hive-dsl.result :as r]
            [vtranslate.engine.adapters.transcriber.whisper-jni :as sut]))

(deftest transcribe-with-spans-slices-and-offsets
  (let [seen (atom [])
        opts (atom [])
        fake (fn [_model-path _use-gpu? samples _language run-opts]
               (swap! seen conj (alength ^floats samples))
               (swap! opts conj run-opts)
               (r/ok [{:start-ms 10 :end-ms 20 :text "x"}]))
        samples (float-array 48000)
        res (#'sut/transcribe-with-spans fake "model.bin" false samples 16000 "en"
                                         [{:start-ms 1000 :end-ms 1500}
                                          {:start-ms 2500 :end-ms 3000}] 0
                                         {:threads 7})]
    (is (r/ok? res))
    (is (= [8000 8000] @seen))
    (is (= [{:threads 7} {:threads 7}] @opts)
        "the thread budget reaches every span, not just the first")
    (is (= [{:start-ms 1010 :end-ms 1020 :text "x"}
            {:start-ms 2510 :end-ms 2520 :text "x"}]
           (:ok res)))))

(deftest transcribe-with-spans-falls-back-to-whole-clip
  (let [seen (atom nil)
        fake (fn [_model-path _use-gpu? samples _language _run-opts]
               (reset! seen (alength ^floats samples))
               (r/ok [{:start-ms 0 :end-ms 10 :text "whole"}]))
        samples (float-array 1234)
        res (#'sut/transcribe-with-spans fake "model.bin" false samples 16000 nil nil 0 nil)]
    (is (r/ok? res))
    (is (= 1234 @seen))
    (is (= [{:start-ms 0 :end-ms 10 :text "whole"}] (:ok res)))))

(defn- scripted-decoder
  "A stand-in for the native decode behind the adapter's injected seam. It
   answers by WINDOW (first sample value) and by the language it is asked for,
   and records every [window language] call."
  [calls script]
  (fn [_model-path _use-gpu? ^floats samples language _run-opts]
    (let [window (long (aget samples 0))]
      (swap! calls conj [window language])
      (r/ok (get-in script [window language] (get-in script [window :any]))))))

(defn- windows
  "16 kHz samples in which window i (1 s each) is filled with the value i."
  [n]
  (float-array (mapcat #(repeat 16000 (float %)) (range n))))

(def ^:private one-second-spans
  [{:start-ms 0 :end-ms 1000} {:start-ms 1000 :end-ms 2000}])

(deftest auto-language-detects-and-tags-each-window
  (let [calls (atom [])
        fake  (scripted-decoder calls
                                {0 {:any [{:start-ms 0 :end-ms 900 :text "einer der berühmtesten Reden seines Lebens."}]}
                                 1 {:any [{:start-ms 0 :end-ms 900 :text "wherever they may live, our citizens of Berlin."}]}})
        res   (#'sut/transcribe-with-spans fake "m" false (windows 2) 16000 nil
                                           one-second-spans 0 nil)]
    (is (r/ok? res))
    (is (= [[0 nil] [1 nil]] @calls)
        "each window is decoded on its own, asked to detect (nil => auto)")
    (is (= ["de" "en"] (mapv :language (:ok res)))
        "every segment carries the language its window was spoken in")))

(deftest forced-language-window-with-a-foreign-placeholder-is-decoded-again
  (let [calls (atom [])
        fake  (scripted-decoder calls
                                {0 {"en" [{:start-ms 0 :end-ms 900 :text "I take pride in the words"}]}
                                 1 {"en"   [{:start-ms 0 :end-ms 900 :text "[speaking German]"}]
                                    "de"   [{:start-ms 0 :end-ms 900 :text "Das hat die Menschen beeindruckt."}]}})
        res   (#'sut/transcribe-with-spans fake "m" false (windows 2) 16000 "en"
                                           one-second-spans 0 nil)]
    (is (= [[0 "en"] [1 "en"] [1 "de"]] @calls)
        "only the placeholder window is decoded again, in the language it names")
    (is (= ["I take pride in the words" "Das hat die Menschen beeindruckt."]
           (mapv :text (:ok res))))
    (is (= [nil "de"] (mapv :language (:ok res))))))

(deftest unnamed-foreign-placeholder-retries-with-detection
  (let [calls (atom [])
        fake  (scripted-decoder calls
                                {0 {"en"   [{:start-ms 0 :end-ms 900 :text "(speaking foreign language)"}]
                                    "auto" [{:start-ms 0 :end-ms 900 :text "Ich bin ein Berliner"}]}})
        res   (#'sut/transcribe-with-spans fake "m" false (windows 1) 16000 "en"
                                           [{:start-ms 0 :end-ms 1000}] 0 nil)]
    (is (= [[0 "en"] [0 "auto"]] @calls))
    (is (= [{:start-ms 0 :end-ms 900 :text "Ich bin ein Berliner" :language "de"}] (:ok res)))))

(deftest a-retry-that-does-not-help-keeps-the-first-hypothesis
  (let [calls (atom [])
        fake  (scripted-decoder calls
                                {0 {:any [{:start-ms 0 :end-ms 900 :text "(speaking foreign language)"}]}})
        res   (#'sut/transcribe-with-spans fake "m" false (windows 1) 16000 "en"
                                           [{:start-ms 0 :end-ms 1000}] 0 nil)]
    (is (= [[0 "en"] [0 "auto"]] @calls))
    (is (= ["(speaking foreign language)"] (mapv :text (:ok res)))
        "nothing is deleted: the placeholder stays for hygiene to mark")))
