(ns vtranslate.engine.adapters.qwen-trifecta-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.schema.port-values :as p]
            [vtranslate.engine.adapters.transcriber.qwen3-asr :as q]
            [vtranslate.engine.adapters.transcriber.support :as sup]
            [vtranslate.engine.port.transcriber :as asr]))
(defn language-observation [[tag expected :as in]] {:in in :out (q/->language-name tag)})
(deftrifecta ->language-name language-observation
  {:golden-path "test/golden/adapters-qwen-language.edn"
   :cases {:nil [nil nil] :blank [" " nil] :region ["EN-US" "English"] :unknown ["he" nil]}
   :gen (gen/elements [["en" "English"] ["EN-US" "English"] ["zh-CN" "Chinese"] ["DE" "German"] ["he" nil]])
   :pred (fn [{[tag expected] :in out :out}]
           (and (m/validate [:maybe :string] out) (= expected out)
                (= out (q/->language-name (.toLowerCase ^String tag java.util.Locale/ROOT)))))})
(defn spans-observation [durations]
  (let [spans (mapv (fn [i n] {:start-ms (* i 100) :end-ms (+ (* i 100) n)}) (range) durations)
        raw (q/transcribe-with-spans (fn [& _] {:ok "fixture"}) :fixture nil
                                     (float-array (* 1600 (max 1 (count spans)))) 16000 "en" spans 0)]
    {:in durations :out {:segments (sup/normalize-segments (:ok raw))}}))
(deftrifecta transcribe-with-spans spans-observation
  {:golden-path "test/golden/adapters-qwen-spans.edn"
   :cases {:whole [] :two [50 80]}
   :gen (gen/vector (gen/choose 1 100) 1 8)
   :pred (fn [{durations :in out :out}]
           (and (m/validate p/Transcription out) (= (count durations) (count (:segments out)))
                (= durations (mapv #(- (:end-ms %) (:start-ms %)) (:segments out)))))})
(deftest offline-qwen-port
  (with-redefs [sup/read-wav-mono-floats (fn [_] {:ok {:samples (float-array 1600) :sample-rate 16000}})
                clojure.core/requiring-resolve (fn [_] (atom (fn [& _] {:ok "fixture"})))]
    (let [result (asr/transcribe (q/->Qwen3AsrTranscriber :fixture nil 0) "fixture.wav" "en" {})]
      (is (m/validate p/TranscribeResult result))
      (is (= 100 (get-in result [:ok :segments 0 :end-ms]))))))
