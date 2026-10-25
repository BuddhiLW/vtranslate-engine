(ns vtranslate.engine.adapters.sherpa-trifecta-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.schema.port-values :as p]
            [vtranslate.engine.adapters.transcriber.sherpa-onnx :as s]
            [vtranslate.engine.adapters.transcriber.support :as sup]
            [vtranslate.engine.port.transcriber :as asr]))
(defn observation [[duration text :as in]]
  {:in in :out (vec (s/result->raw-segments {:text text :tokens [] :timestamps []} duration))})
(deftrifecta result->raw-segments observation
  {:golden-path "test/golden/adapters-sherpa-raw.edn"
   :cases {:zero [0 "hello"] :duration [1000 "שלום"]}
   :gen (gen/tuple (gen/choose 0 100000) gen/string-alphanumeric)
   :pred (fn [{[duration text] :in out :out}]
           (and (m/validate [:vector [:map [:start :double] [:end :double] [:text :string]]] out)
                (= [{:start 0.0 :end (/ (double duration) 1000.0) :text text}] out)))})
(deftest offline-sherpa-port
  (with-redefs [sup/read-wav-mono-floats (fn [_] {:ok {:samples (float-array 1600) :sample-rate 16000}})
                sup/wav-duration-ms (constantly 100)
                clojure.core/requiring-resolve (fn [_] (fn [& _] {:text "fixture" :tokens [] :timestamps []}))]
    (let [result (asr/transcribe (s/->SherpaOnnxTranscriber {}) "fixture.wav" "en" {})]
      (is (m/validate p/TranscribeResult result))
      (is (= 100 (get-in result [:ok :segments 0 :end-ms]))))))
