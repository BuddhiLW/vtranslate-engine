(ns vtranslate.engine.adapters.transcriber.whisper-jni-test
  (:require [clojure.test :refer [deftest is]]
            [hive-dsl.result :as r]
            [vtranslate.engine.adapters.transcriber.whisper-jni :as sut]
            [vtranslate.engine.port.transcriber :as p.asr]))

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
                                         {:threads 7} #(p.asr/decoded {} %1 %2))]
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
                                         #(p.asr/decoded {} %1 %2))]
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

(deftest no-route-hook-decodes-once-in-the-asked-language
  (let [calls (atom [])
        fake  (scripted-decoder calls {0 {:any [{:start-ms 0 :end-ms 900 :text "(speaking foreign language)"}]}})
        res   (#'sut/transcribe-with-spans fake "m" false (windows 1) 16000 nil
                                           [{:start-ms 0 :end-ms 1000}] 0 nil #(p.asr/decoded {} %1 %2))]
    (is (= [[0 nil]] @calls))
    (is (= ["(speaking foreign language)"] (mapv :text (:ok res)))
        "no addon, no re-decode: the text is kept as decoded")))

(deftest the-port-runs-both-hooks-over-each-window-decode
  (let [calls (atom [])
        seen  (atom [])
        fake  (scripted-decoder calls
                                {0 {:any [{:start-ms 0 :end-ms 900 :text "one" :compression_ratio 3.1}]}
                                 1 {:any [{:start-ms 0 :end-ms 900 :text "two" :compression_ratio 3.1}]}})
        opts  {:asr/route-window (fn [decode language]
                                   (r/let-ok [raw (decode language)]
                                     (r/ok (mapv #(assoc % :routed? true) raw))))
               :asr/clean (fn [raw o]
                            (swap! seen conj [(mapv :text raw)
                                              (:compression_ratio (first raw))
                                              (every? :routed? raw)
                                              (contains? o :asr/route-window)])
                            (mapv #(assoc % :cleaned? true) raw))}
        res   (#'sut/transcribe-with-spans fake "m" false (windows 2) 16000 "en"
                                           one-second-spans 0 nil #(p.asr/decoded opts %1 %2))]
    (is (= [[["one"] 3.1 true true] [["two"] 3.1 true true]] @seen)
        "clean runs once per window, AFTER the route, on that decode's own metrics")
    (is (every? #(and (:routed? %) (:cleaned? %)) (:ok res))
        "and both hooks reach the segments the clip is built from")))

(deftest a-route-may-decode-a-window-widened-and-treated
  (let [seen  (atom [])
        fake  (fn [_model-path _use-gpu? ^floats samples _language _run-opts]
                (swap! seen conj [(alength samples) (aget samples 0)])
                (r/ok [{:start-ms 0 :end-ms 100 :text "w"}]))
        told  (atom nil)
        route (fn [decode language]
                (r/let-ok [raw (decode language)
                           w   (decode language {:widen-ms 500})
                           _   (decode language {:transform (fn [^floats fs]
                                                              (float-array (map #(* 2 %) fs)))})]
                  (reset! told {:wide w :window-ms (:asr/window-ms (meta decode))})
                  (r/ok raw)))
        opts  (p.asr/with-route {} route)
        res   (#'sut/transcribe-with-spans fake "m" false (windows 3) 16000 "en"
                                           [{:start-ms 1000 :end-ms 2000}] 0 nil
                                           (:asr/route-window opts))]
    (is (r/ok? res))
    (is (= [[16000 1.0] [32000 0.0] [16000 2.0]] @seen)
        "as is, grown by 500 ms on each side, and through the transform")
    (is (= [{:start-ms -500 :end-ms -400 :text "w"}] (:wide @told))
        "a widened decode is timed against the window it widens")
    (is (= 1000 (:window-ms @told))
        "a composed route still reads the window's length")
    (is (= [{:start-ms 1000 :end-ms 1100 :text "w"}] (:ok res)))))

(deftest a-route-may-decode-a-window-with-other-weights
  (let [seen  (atom [])
        fake  (fn [model-path _use-gpu? ^floats samples language _run-opts]
                (swap! seen conj [model-path language (alength samples)])
                (r/ok [{:start-ms 0 :end-ms 100 :text (str model-path ":" language)}]))
        route (fn [decode language]
                (r/let-ok [raw (decode language)]
                  (if (= "m:auto" (:text (first raw)))
                    (decode "he" {:model-path "he.bin"})
                    (r/ok raw))))
        opts  (p.asr/with-route {} route)
        res   (#'sut/transcribe-with-spans fake "m" false (windows 2) 16000 "auto"
                                           one-second-spans 0 nil
                                           (:asr/route-window opts))]
    (is (r/ok? res))
    (is (= [["m" "auto" 16000] ["he.bin" "he" 16000]
            ["m" "auto" 16000] ["he.bin" "he" 16000]]
           @seen)
        "the adapter's own weights unless the route names others, window by window")
    (is (= ["he.bin:he" "he.bin:he"] (mapv :text (:ok res)))
        "and the routed decode is what the clip is built from")))

(deftest decode-knobs-set-in-config-reach-the-decode
  ;; no knob set: nothing of ours may override a backend default
  (is (= #{:threads :print-progress?}
         (set (keys (sut/run-opts-for {:transcriber-opts {:use-gpu?   true
                                                          :model-path "models/x.bin"}})))))

  ;; a knob an operator DID set travels to the decode, beside what was always carried
  (let [out (sut/run-opts-for {:transcriber-opts {:threads                     8
                                                  :use-gpu?                    true
                                                  :suppress-non-speech-tokens? true
                                                  :beam-size                   5}})]
    (is (true? (:suppress-non-speech-tokens? out)))
    (is (= 5 (:beam-size out)))
    ;; resolve-threads CLAMPS to the box's core count, so the asked-for 8 is 8
    ;; only on a box with 8 cores. Assert it still resolves, not what this
    ;; machine happens to have.
    (is (= (sut/resolve-threads 8) (:threads out))))

  ;; the :transcriber-opts keys that are NOT knobs never leak into the decode
  (let [out (sut/run-opts-for {:transcriber-opts {:use-gpu?    true
                                                  :model-path  "models/x.bin"
                                                  :span-pad-ms 500}})]
    (is (not (contains? out :use-gpu?)))
    (is (not (contains? out :model-path)))
    (is (not (contains? out :span-pad-ms)))))
