(ns vtranslate.engine.adapters.whisper-ffm-trifecta-test
  "Offline port contract for the Panama FFM whisper.cpp transcriber. The
   library binding and the whisper_full call are the native surface and are
   stubbed: run-whisper answers centisecond-converted raw segments the way the
   real one does, so what is exercised is the record's guardrail path (model
   gate, WAV read, clean hook, normalize-segments). No libwhisper is opened."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.adapters.transcriber.support :as sup]
            [vtranslate.engine.adapters.transcriber.whisper-ffm :as ffm]
            [vtranslate.engine.port.transcriber :as asr]
            [vtranslate.engine.providers.transcriber-registry :as reg]
            [vtranslate.engine.schema.adapters-gates :as g]
            [vtranslate.engine.schema.port-values :as p])
  (:import [java.io File]))

(defn transcribe-observation
  "Run the real record over stubbed native raw segments `raw`."
  [raw]
  (with-redefs-fn {#'ffm/bound-lib (fn [_ _] {:handles {}})
                   #'ffm/run-whisper (fn [_ _ _] raw)
                   #'sup/read-wav-mono-floats (fn [_] {:ok {:samples (float-array 16) :sample-rate 16000}})}
    #(hash-map :in raw
               :out (asr/transcribe (ffm/->WhisperFfmTranscriber "/fixture/libwhisper.so" "/fixture/model.bin" 0)
                                    "fixture.wav" "en" {}))))

(def ^:private raw-gen
  (gen/fmap (fn [parts]
              (loop [ps parts at 0 out []]
                (if-let [[gap len text] (first ps)]
                  (recur (next ps) (+ at gap len) (conj out {:start-ms (+ at gap) :end-ms (+ at gap len) :text text}))
                  out)))
            (gen/vector (gen/tuple (gen/choose 0 500) (gen/choose 0 3000)
                                   (gen/elements ["hello" " world " "" "  " "[MUSIC]" "ok"]))
                        0 8)))

(deftrifecta transcribe transcribe-observation
  {:golden-path "test/golden/adapters-whisper-ffm-transcribe.edn"
   :cases {:empty [] :two [{:start-ms 0 :end-ms 1000 :text " hi"} {:start-ms 1000 :end-ms 2500 :text "there "}]
           :blank [{:start-ms 0 :end-ms 10 :text "   "}]}
   :gen raw-gen
   :pred (fn [{raw :in out :out}]
           (let [segs (get-in out [:ok :segments])]
             (and (m/validate p/TranscribeResult out)
                  (<= (count segs) (count raw))
                  (every? (fn [s] (some #(<= (:start-ms %) (:start-ms s) (:end-ms s) (:end-ms %)) raw)) segs))))})

(deftest whisper-ffm-refusals
  (testing "no model path: refused before the library is bound"
    (with-redefs-fn {#'ffm/bound-lib (fn [_ _] (throw (ex-info "must not bind" {})))}
      #(let [res (asr/transcribe (ffm/->WhisperFfmTranscriber "/x.so" " " 0) "fixture.wav" "en" {})]
         (is (m/validate p/TranscribeResult res))
         (is (= :error/asr-failed (:error res))))))
  (testing "a native throw is a typed ASR error, never an empty transcript"
    (with-redefs-fn {#'ffm/bound-lib (fn [_ _] {:handles {}})
                     #'ffm/run-whisper (fn [_ _ _] (throw (ex-info "whisper_full failed" {:rc 1})))
                     #'sup/read-wav-mono-floats (fn [_] {:ok {:samples (float-array 1) :sample-rate 16000}})}
      #(is (= :error/asr-failed (:error (asr/transcribe (ffm/->WhisperFfmTranscriber "/x.so" "/m.bin" 0)
                                                         "fixture.wav" "en" {})))))))

(deftest whisper-ffm-resolve-gate
  (is (m/validate g/UnavailableError (reg/resolve-transcriber :whisper-ffm {})))
  (is (m/validate g/UnavailableError
                  (reg/resolve-transcriber :whisper-ffm {:transcriber-opts {:lib-path "/nonexistent/libwhisper.so"}})))
  (let [lib (doto (File/createTempFile "libwhisper" ".so") .deleteOnExit)]
    (try
      (let [res (reg/resolve-transcriber :whisper-ffm {:transcriber-opts {:lib-path (.getPath lib)
                                                                          :model-path "/m.bin"
                                                                          :params-struct-bytes 256}})]
        (is (= {:lib-path (.getPath lib) :model-path "/m.bin" :params-bytes 256} (into {} (:ok res)))))
      (finally (.delete lib)))))
