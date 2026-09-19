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
                                         {:threads 7} #'sut/plain-route)]
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
        res (#'sut/transcribe-with-spans fake "model.bin" false samples 16000 nil nil 0 nil
                                         #'sut/plain-route)]
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

(deftest each-window-is-decoded-through-the-route-hook
  (let [calls (atom [])
        fake  (scripted-decoder calls
                                {0 {:any [{:start-ms 0 :end-ms 900 :text "first"}]}
                                 1 {"en" [{:start-ms 0 :end-ms 900 :text "second"}]
                                    "de" [{:start-ms 0 :end-ms 900 :text "zweite"}]}})
        route (fn [decode language]
                (r/let-ok [raw (decode language)]
                  (if (= "second" (:text (first raw)))
                    (r/let-ok [again (decode "de")]
                      (r/ok (mapv #(assoc % :language "de") again)))
                    (r/ok raw))))
        res   (#'sut/transcribe-with-spans fake "m" false (windows 2) 16000 "en"
                                           one-second-spans 0 nil route)]
    (is (= [[0 "en"] [1 "en"] [1 "de"]] @calls)
        "the route decides how each window is decoded, window by window")
    (is (= [["first" nil] ["zweite" "de"]] (mapv (juxt :text :language) (:ok res))))))

(deftest the-plain-route-decodes-once-in-the-asked-language
  (let [calls (atom [])
        fake  (scripted-decoder calls {0 {:any [{:start-ms 0 :end-ms 900 :text "(speaking foreign language)"}]}})
        res   (#'sut/transcribe-with-spans fake "m" false (windows 1) 16000 nil
                                           [{:start-ms 0 :end-ms 1000}] 0 nil #'sut/plain-route)]
    (is (= [[0 nil]] @calls))
    (is (= ["(speaking foreign language)"] (mapv :text (:ok res)))
        "no addon, no re-decode: the text is kept as decoded")))
