(ns vtranslate.engine.adapters.nemo-trifecta-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.schema.port-values :as p]
            [vtranslate.engine.adapters.transcriber.nemo-python :as n]
            [vtranslate.engine.adapters.transcriber.support :as sup]
            [vtranslate.engine.port.transcriber :as asr]))
(defn language-observation [tag] {:in tag :out (n/->lang-code tag)})
(deftrifecta ->lang-code language-observation
  {:golden-path "test/golden/adapters-nemo-language.edn"
   :cases {:nil nil :blank " " :region "EN-US" :primary "he"}
   :gen (gen/elements ["en" "EN-US" "he-IL" "DE-de" "fr"])
   :pred (fn [{:keys [in out]}]
           (and (m/validate [:maybe :string] out)
                (= out (str/lower-case (first (str/split in #"-"))))
                (= out (n/->lang-code out))))})
(deftest offline-nemo-port
  (let [seen (atom []) native (fn [model path src tgt opts] (swap! seen conj [model src tgt opts]) {:ok "fixture"})]
    (with-redefs [sup/read-wav-mono-floats (fn [_] {:ok {:samples (float-array 1600) :sample-rate 16000}})
                  clojure.core/requiring-resolve (fn [_] native)]
      ;; decode-slice is the scratch-file IO seam; the adapter and span mapper remain real.
      (with-redefs-fn {#'n/decode-slice (fn [f model _ _ src tgt] (f model "fixture.wav" src tgt))}
        (fn []
          (let [result (asr/transcribe (n/->NemoTranscriber "canary" "he" 0 "python" nil "cpu" true)
                                      "fixture.wav" "EN-US" {})]
            (is (m/validate p/TranscribeResult result))
            (is (= [{:start-ms 0 :end-ms 100 :text "fixture" :confidence 1.0}] (get-in result [:ok :segments])))
            (is (= ["canary" "en" "he"] (subvec (first @seen) 0 3)))))))))
