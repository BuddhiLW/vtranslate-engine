(ns vtranslate.engine.native.whisper-jni.whisper-jni-native-test
  "Native contract for transcriber.whisper-jni-native under :ffmpeg:whisper-jni,
   on CPU (use-gpu? false) with local whisper.cpp weights (see speech-fixture).

   whisper.cpp does not decode bit-exactly from run to run on this build (even
   single-threaded at temperature 0 the text and the timestamps drift), so the
   transcription golden snapshots only the run-independent facts: the decode
   succeeded, every segment starts inside the window, the segments are ordered,
   and the words are mostly the clip's own. The relational property checks the
   same over generated windows of the speech clip, plus the schema."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.adapters.transcriber.whisper-jni :as outer]
            [vtranslate.engine.adapters.transcriber.whisper-jni-native :as sut]
            [vtranslate.engine.contract.ports-contract :as contract]
            [vtranslate.engine.native.media-fixture :as fx]
            [vtranslate.engine.native.speech-fixture :as sp]
            [vtranslate.engine.port.transcriber :as p.asr]
            [vtranslate.engine.schema.native-asr :as na]
            [vtranslate.engine.schema.port-values :as pv]))

(def ^:private run-opts {:threads 4 :print-progress? false})

(def reference-words
  "Every word of the clip's reference text (the 24 s JFK inaugural excerpt),
   whisper's non-speech tags, and the base weights' usual mishearings of the
   cut-off first word."
  (set (re-seq #"[a-z]+"
               "endeavor endeavour devotion we bring to this will we'll ll light our country
                and all who serve it the glow from that fire can truly light the world and so
                my fellow americans america ask not what your country can do for you ask what
                you can do for your country cheering cheers applause audience blank audio
                music in ever devil deva")))

(defn- decoded-words [segments]
  (re-seq #"[a-z]+" (str/lower-case (str/join " " (map :text segments)))))

(defn- reference-ratio
  "Share of decoded words that belong to the clip's reference text (0 when none)."
  [segments]
  (let [ws (decoded-words segments)]
    (if (seq ws) (/ (count (filter reference-words ws)) (double (count ws))) 0.0)))

(defn window-observation
  "Decode [start-s, start-s+dur-s) of the speech clip. => {:in :out :raw}.

   Only segment STARTS are checked against the window: whisper.cpp's raw end
   timestamps can overrun a short window (a 4 s window measured ending at
   5400 ms), and this namespace hands them on unclamped by contract;
   support/normalize-segments upstream is what bounds them."
  [[start-s dur-s]]
  (let [xs  (sp/window start-s dur-s)
        ms  (quot (* 1000 (alength xs)) 16000)
        res (sut/transcribe-samples @sp/whisper-model false xs "en" run-opts)
        seg (:ok res)]
    {:in  [start-s dur-s]
     :out {:ok?             (contains? res :ok)
           :starts-inside?  (every? #(<= 0 (:start-ms %) ms) seg)
           :ordered?        (pv/ordered? (vec seg))
           :mostly-speech?  (>= (reference-ratio seg) 0.5)}
     :raw res}))

(defn- holds?
  "Schema-valid, successful, starts inside the window, ordered, and at least
   half the decoded words are the clip's own. A window cut mid-word lets
   whisper finish the phrase with words of its own (\"ask not to let you
   know\"), so a strict subset would test the cut, not the adapter."
  [{:keys [out raw]}]
  (and (m/validate na/WhisperWindowObservation out)
       (m/validate na/WhisperRawResult raw)
       (every? true? (vals out))))

(deftrifecta transcribe-samples window-observation
  {:golden-path "test/golden/native-whisper-jni-windows.edn"
   :cases {:opening [0 6]
           :ask-not [10 7]
           :whole   [0 24]}
   :xf #(dissoc % :raw)
   :gen (gen/tuple (gen/choose 0 18) (gen/choose 3 6))
   :num-tests 6
   :pred holds?})

(deftrifecta lang-code sut/lang-code
  {:golden-path "test/golden/native-whisper-jni-lang-code.edn"
   :cases {:bcp47 "en-US" :upper "PT-br" :plain "de" :nil nil :blank "  " :auto "auto"
           :script "zh-Hant-TW"}
   :gen (gen/one-of [(gen/return nil) gen/string-alphanumeric
                     (gen/fmap #(str % "-" (str/upper-case %)) gen/string-alphanumeric)])
   :pred (fn [code] (m/validate na/WhisperLang code))})

(deftest default-threads-leaves-two-cores-and-never-drops-below-one
  (let [n (sut/default-threads) cores (.availableProcessors (Runtime/getRuntime))]
    (is (pos-int? n))
    (is (= n (max 1 (- cores 2))))))

(deftest non-speech-decodes-to-no-reference-speech
  (testing "silence and faint noise: an ok Result, whisper may tag it, never the clip's words"
    (doseq [xs [(float-array 16000) (float-array 32000) (sp/noise 32000 0.01 7)]]
      (let [res (sut/transcribe-samples @sp/whisper-model false xs "en" run-opts)]
        (is (m/validate na/WhisperRawResult res) (pr-str res))
        (is (not-any? #{"country" "americans" "fellow" "light"} (decoded-words (:ok res)))
            (pr-str res))))))

(deftest refusals-are-asr-failed-results
  (is (= :error/asr-failed
         (:error (sut/transcribe-samples @sp/whisper-model false nil "en" run-opts))))
  (fx/with-temp-dir [dir]
    (let [res (sut/transcribe-samples (str dir "/missing.bin") false (float-array 1600) "en" run-opts)]
      (is (m/validate na/WhisperRawResult res))
      (is (= :error/asr-failed (:error res))))))

(deftest outer-record-satisfies-the-transcriber-port-natively
  (let [t (outer/->WhisperLocalTranscriber @sp/whisper-model false 0 run-opts)]
    (contract/check-transcriber t {:path @sp/speech-wav} "en")
    (testing "per-span decode lands in absolute clip time"
      (let [res (p.asr/transcribe t {:path @sp/speech-wav} "en"
                                  {:spans [{:start-ms 0 :end-ms 5000} {:start-ms 10000 :end-ms 15000}]})]
        (is (m/validate pv/TranscribeResult res) (pr-str res))
        (is (seq (get-in res [:ok :segments])))
        (is (every? #(or (<= 0 (:start-ms %) 5000) (<= 10000 (:start-ms %) 15000))
                    (get-in res [:ok :segments])))))))
