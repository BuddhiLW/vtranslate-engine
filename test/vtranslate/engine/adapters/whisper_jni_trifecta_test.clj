(ns vtranslate.engine.adapters.whisper-jni-trifecta-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.adapters.transcriber.whisper-jni :as j]
            [vtranslate.engine.adapters.transcriber.support :as sup]
            [vtranslate.engine.schema.port-values :as p]
            [vtranslate.engine.port.transcriber :as asr]))
(defn threads-observation [[requested available :as in]]
  {:in in :out (j/resolve-threads requested available)})
(deftrifecta resolve-threads threads-observation
  {:golden-path "test/golden/adapters-jni-threads.edn"
   :cases {:auto [:auto 8] :nil [nil 8] :zero [0 8] :oversubscribed [100 8]}
   :gen (gen/tuple (gen/one-of [(gen/return nil) (gen/return :auto) (gen/choose -10 100)]) (gen/choose 1 32))
   :pred (fn [{[requested available] :in out :out}]
           (and (m/validate [:maybe [:int {:min 1}]] out)
                (if (or (nil? requested) (= :auto requested)) (nil? out)
                    (and (<= out available) (= out (max 1 (min requested available)))))))})
(defn opts-observation [opts]
  {:in opts :out (with-redefs [j/available-cores (constantly 8)]
                  (j/run-opts-for {:transcriber-opts opts}))})
(deftrifecta run-opts-for opts-observation
  {:golden-path "test/golden/adapters-jni-options.edn"
   :cases {:default {} :explicit {:threads 100 :print-progress? false :beam-size 4}}
   :gen (gen/hash-map :threads (gen/choose -2 32) :print-progress? gen/boolean :beam-size (gen/choose 1 8))
   :pred (fn [{:keys [in out]}]
           (and (m/validate [:map [:threads [:maybe :int]] [:print-progress? :boolean]] out)
                (= (j/resolve-threads (:threads in) 8) (:threads out))
                (= (boolean (get in :print-progress? true)) (:print-progress? out))
                (= (sup/resolve-decode-opts in) (dissoc out :threads :print-progress?))))})
(deftest offline-jni-port
  ;; Replace lazy native lookup, not the transcribe method under test.
  (let [fake (fn [& _] {:ok [{:start-ms 0 :end-ms 50 :text "fixture"}]})]
    (with-redefs [sup/read-wav-mono-floats (fn [_] {:ok {:samples (float-array 1600) :sample-rate 16000}})
                  clojure.core/requiring-resolve (fn [_] (atom fake))]
      (let [result (asr/transcribe (j/->WhisperLocalTranscriber "fixture.bin" false 0 {}) "fixture.wav" "en" {})]
        (is (m/validate p/TranscribeResult result))
        (is (= ["fixture"] (mapv :text (get-in result [:ok :segments]))))))))
