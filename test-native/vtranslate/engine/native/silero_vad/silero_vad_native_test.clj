(ns vtranslate.engine.native.silero-vad.silero-vad-native-test
  "Native contract for segmenter.silero-vad-native/speech-probs under
   :ffmpeg:silero-vad with the local Silero VAD ONNX model (see speech-fixture).
   ONNX Runtime CPU inference is deterministic, so the goldens snapshot the
   probability summary exactly.

   Relational property over {:kind :seconds :rate}: one probability per
   512-sample (16 kHz) / 256-sample (8 kHz) window, rounding up; every
   probability in [0,1]; a repeat run is identical; silence never crosses the
   0.5 speech threshold, while real speech does."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.adapters.segmenter.silero-vad :as outer]
            [vtranslate.engine.adapters.segmenter.silero-vad-native :as sut]
            [vtranslate.engine.native.media-fixture :as fx]
            [vtranslate.engine.native.speech-fixture :as sp]
            [vtranslate.engine.port.segmenter :as p.seg]
            [vtranslate.engine.schema.native-asr :as na]
            [vtranslate.engine.schema.port-values :as pv]))

(defn- signal
  "Mono float samples of `kind` lasting `seconds` at `rate` Hz. :speech is the
   speech clip from second 12, which is voiced throughout (second 10 is a
   pause), decimated for 8 kHz."
  ^floats [kind seconds rate]
  (let [n (long (* rate seconds))]
    (case kind
      :silence (float-array n)
      :noise   (sp/noise n 0.005 42)
      :speech  (let [xs (sp/window 12 seconds)
                     step (quot 16000 rate)
                     out (float-array (min n (quot (alength xs) step)))]
                 (dotimes [i (alength out)] (aset out i (aget xs (* i step))))
                 out))))

(defn probs-observation
  "speech-probs over a generated signal, twice. => {:in :out :probs}."
  [{:keys [kind seconds rate] :as in}]
  (let [xs (signal kind seconds rate)
        p1 (sut/speech-probs @sp/silero-model xs rate)
        p2 (sut/speech-probs @sp/silero-model xs rate)]
    {:in    in
     :out   {:windows        (count p1)
             :max-prob       (double (reduce max 0.0 p1))
             :speech-windows (count (filter #(> % 0.5) p1))
             :deterministic? (= p1 p2)}
     :probs p1
     :samples (alength xs)}))

(defn- holds? [{{:keys [kind rate]} :in out :out probs :probs n :samples}]
  (let [window (if (= 16000 rate) 512 256)]
    (and (m/validate na/SpeechProbsObservation out)
         (m/validate na/SpeechProbs probs)
         (:deterministic? out)
         (= (:windows out) (long (Math/ceil (/ n (double window)))))
         (case kind
           :silence (zero? (:speech-windows out))
           :noise   (< (:speech-windows out) (max 1 (quot (:windows out) 10)))
           :speech  (pos? (:speech-windows out))))))

(deftrifecta speech-probs probs-observation
  {:golden-path "test/golden/native-silero-speech-probs.edn"
   :cases {:silence-16k {:kind :silence :seconds 1 :rate 16000}
           :silence-8k  {:kind :silence :seconds 1 :rate 8000}
           :speech-16k  {:kind :speech :seconds 4 :rate 16000}
           :speech-8k   {:kind :speech :seconds 4 :rate 8000}}
   :xf #(dissoc % :probs)
   :gen (gen/hash-map :kind (gen/elements [:silence :noise :speech])
                      :seconds (gen/choose 1 4)
                      :rate (gen/elements [8000 16000]))
   :num-tests 12
   :pred holds?})

(deftest partial-and-unsupported-input
  (testing "a tail shorter than one window still yields one probability"
    (is (= 1 (count (sut/speech-probs @sp/silero-model (float-array 100) 16000))))
    (is (= 2 (count (sut/speech-probs @sp/silero-model (float-array 513) 16000))))
    (is (= [] (sut/speech-probs @sp/silero-model (float-array 0) 16000))))
  (testing "only 8 kHz and 16 kHz are accepted, before any session is built"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"8kHz/16kHz"
                          (sut/speech-probs "/does/not/matter.onnx" (float-array 10) 44100))))
  (testing "a missing model throws from ONNX Runtime (the record maps it to a Result)"
    (fx/with-temp-dir [dir]
      (is (thrown? Exception (sut/speech-probs (str dir "/missing.onnx") (float-array 10) 16000))))))

(deftest outer-segmenter-finds-speech-spans-natively
  (let [seg (outer/make-segmenter {:segmenter-opts {:model-path @sp/silero-model}})
        res (p.seg/segment (:ok seg) {:path @sp/speech-wav} {})]
    (is (m/validate pv/SegmentResult res) (pr-str res))
    (is (seq (get-in res [:ok :spans])))
    (is (every? #(<= 0 (:start-ms %) (:end-ms %) 24100) (get-in res [:ok :spans])))
    (fx/with-temp-dir [dir]
      (let [wav (sp/write-wav! (str dir "/silence.wav") (float-array 32000) 16000)]
        (is (= {:ok {:spans []}} (p.seg/segment (:ok seg) {:path wav} {})))))))
