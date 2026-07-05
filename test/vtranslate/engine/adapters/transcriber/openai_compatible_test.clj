(ns vtranslate.engine.adapters.transcriber.openai-compatible-test
  "The OpenAI-compatible ASR adapter is the real, no-native-dep backend. HTTP is
   mocked (with-redefs on the private post fn) so these run offline: they pin the
   verbose_json -> contract mapping, the text-only spanning fallback, fail-loud on
   transport error, and the resolve-time capability gate (key-requiring hosts vs a
   keyless local server)."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-dsl.result :as r]
            [vtranslate.engine.contract.ports-contract :as ct]
            [vtranslate.engine.port.transcriber :as p.asr]
            [vtranslate.engine.providers.transcriber-registry :as reg]
            [vtranslate.engine.adapters.transcriber.support :as sup]
            [vtranslate.engine.adapters.transcriber.openai-compatible :as oai]))

(defn- with-tmp-wav [f]
  (let [tmp (java.io.File/createTempFile "vt-asr-" ".wav")]
    (try (spit tmp "RIFFdummy") (f (.getPath tmp))
         (finally (.delete tmp)))))

(deftest verbose-json-maps-to-contract
  (with-tmp-wav
    (fn [path]
      (with-redefs [oai/post-multipart
                    (fn [_ _ _] (r/ok {:segments [{:start 0.0 :end 1.4 :text "Hello"}
                                                  {:start 1.4 :end 3.2 :text "world"}]}))]
        (let [impl (oai/->OpenAiTranscriber "http://mock" "whisper-large-v3" "sk-fake")]
          (ct/check-transcriber impl path "en")   ; LSP contract
          (let [segs (:segments (:ok (p.asr/transcribe impl path "en" {})))]
            (is (= [[0 1400] [1400 3200]] (mapv (juxt :start-ms :end-ms) segs))
                "second-precision timestamps convert to ms")))))))

(deftest text-only-spans-whole-clip
  (with-tmp-wav
    (fn [path]
      (with-redefs [oai/post-multipart (fn [_ _ _] (r/ok {:text "just text"}))
                    sup/wav-duration-ms (constantly 5000)]
        (let [segs (:segments (:ok (p.asr/transcribe
                                    (oai/->OpenAiTranscriber "http://mock" "m" "k") path "en" {})))]
          (is (= [{:start-ms 0 :end-ms 5000}] (mapv #(select-keys % [:start-ms :end-ms]) segs))
              "a segment-less reply becomes one clip-spanning segment"))))))

(deftest transport-error-fails-loud
  (with-tmp-wav
    (fn [path]
      (with-redefs [oai/post-multipart (fn [_ _ _] (r/err :error/asr-failed {:reason "HTTP 401"}))]
        (is (= :error/asr-failed
               (:error (p.asr/transcribe (oai/->OpenAiTranscriber "http://mock" "m" "k") path "en" {})))
            "never a fake transcript on failure")))))

(deftest capability-gate
  (testing "a key-requiring provider with no key is unavailable at resolve time"
    (is (= :error/transcriber-unavailable (:error (reg/resolve-transcriber :openai-whisper {}))))
    (is (= :error/transcriber-unavailable (:error (reg/resolve-transcriber :groq {})))))
  (testing "a key provided via config makes it resolvable"
    (is (r/ok? (reg/resolve-transcriber :groq {:transcriber-opts {:secret-env "PATH"}}))
        "PATH is always set -> resolve-key finds a value -> resolvable"))
  (testing "the keyless local server is always resolvable"
    (is (r/ok? (reg/resolve-transcriber :whisper-server {})))))
