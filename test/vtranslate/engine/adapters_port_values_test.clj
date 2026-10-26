(ns vtranslate.engine.adapters-port-values-test
  "Offline fixture transports: execute real adapter protocol methods, validate output."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [aleph.http :as http]
            [vtranslate.engine.schema.port-values :as p]
            [vtranslate.engine.schema.adapters-boundaries :as wire]
            [vtranslate.engine.adapters.transcriber.openai-compatible :as openai]
            [vtranslate.engine.adapters.transcriber.sherpa-onnx :as sherpa]
            [vtranslate.engine.adapters.transcriber.qwen3-asr :as qwen]
            [vtranslate.engine.adapters.transcriber.nemo-python :as nemo]
            [vtranslate.engine.adapters.transcriber.support :as sup]
            [vtranslate.engine.adapters.model-host.speaches :as speaches]
            [vtranslate.engine.adapters.translator.identity :as identity]
            [vtranslate.engine.port.transcriber :as asr]
            [vtranslate.engine.port.model-host :as host]
            [vtranslate.engine.port.translator :as tr]))

(defn normalization-observation [raw]
  {:in raw :out (sup/normalize-segments raw)})
(deftrifecta normalization normalization-observation
  {:golden-path "test/golden/adapters-normalization.edn"
   :cases {:out-of-order [{:start-ms 20 :end-ms 30 :text "second"}
                          {:start-ms 0 :end-ms 40 :text "first"}]
           :non-speech [{:start-ms 0 :end-ms 100 :text "[BLANK_AUDIO]"}
                        {:start-ms 20 :end-ms 50 :text "speech"}]}
   :gen (gen/vector (gen/hash-map :start-ms (gen/choose -100 10000)
                                  :end-ms (gen/choose -100 12000)
                                  :text (gen/elements ["a" " b " "[BLANK_AUDIO]" ""])) 0 30)
   :pred (fn [{raw :in out :out}]
           (and (m/validate p/Segments out)
                (= out (sup/normalize-segments out))
                (<= (count out) (count raw))))})

(defn sherpa-observation [[reply duration :as in]]
  {:in in :out (sup/normalize-segments (sherpa/result->raw-segments reply duration) {:unit :s})})
(deftrifecta sherpa-reply sherpa-observation
  {:golden-path "test/golden/adapters-sherpa.edn"
   :cases {:text-only [{:text "hello"} 1000]
           :tokens [{:text "hello world" :tokens ["▁hello" "▁world"] :timestamps [0.0 0.5]} 1000]}
   :gen (gen/fmap (fn [duration] [{:text "hello world" :tokens ["▁hello" "▁world"]
                                  :timestamps [0.0 (/ duration 2000.0)]} duration])
                  (gen/choose 2 100000))
   :pred (fn [{[_ duration] :in out :out}]
           (and (m/validate p/Segments out)
                (every? #(<= 0 (:start-ms %) (:end-ms %) duration) out)
                (= ["hello" "world"] (mapv :text out))))})

(defn language-observation [tag]
  {:in tag :out [(qwen/->language-name tag) (nemo/->lang-code tag)]})
(deftrifecta language-routing language-observation
  {:golden-path "test/golden/adapters-language.edn"
   :cases {:regional "en-US" :uppercase "DE" :blank " " :absent nil}
   :gen (gen/elements ["en" "en-US" "DE" "de-DE" "fr" "fr-FR"])
   :pred (fn [{tag :in out :out}]
           (= out [(qwen/->language-name (.toLowerCase ^String tag))
                   (nemo/->lang-code (.toLowerCase ^String tag))]))})

(defn base-observation [url]
  {:in url :out (speaches/base-url url)})
(deftrifecta server-root base-observation
  {:golden-path "test/golden/adapters-server-root.edn"
   :cases {:endpoint "http://fixture/v1/audio/transcriptions" :trailing "http://fixture/"}
   :gen (gen/fmap #(str "http://fixture/" % "/v1/audio/transcriptions") gen/string-alphanumeric)
   :pred (fn [{url :in out :out}]
           (= url (str out "/v1/audio/transcriptions")))})

(deftest openai-adapter-with-fixture-transport
  (let [reply "{\"segments\":[{\"start\":0,\"end\":0.5,\"text\":\"hello\"},{\"start\":0.5,\"end\":1,\"text\":\"world\"}]}"
        seen (atom [])]
    (with-redefs-fn {#'openai/read-all-bytes (fn [_] {:ok (byte-array 44)})
                    #'sup/wav-duration-ms (constantly 1000)
                    #'http/post (fn [url opts]
                                  (swap! seen conj [url opts])
                                  (delay {:status 200 :body (java.io.StringReader. reply)}))}
      (fn []
        (let [out (asr/transcribe (openai/->OpenAiTranscriber "http://fixture" "test" nil {})
                                  "fixture.wav" "en" {})]
          (is (m/validate p/TranscribeResult out))
          (is (= ["hello" "world"] (mapv :text (get-in out [:ok :segments]))))
          (is (= 1 (count @seen)))
          (is (m/validate wire/MultipartParts (get-in @seen [0 1 :multipart]))))))))

(deftest speaches-status-fixtures
  (doseq [[status expected] [[204 :unloaded] [404 :absent] [409 :busy]]]
    (with-redefs [http/request (fn [_] (delay {:status status :body nil}))]
      (let [out (host/unload! (speaches/host "http://fixture") "model")]
        (is (m/validate p/UnloadResult out))
        (is (= expected (:ok out))))))
  (with-redefs [http/request (fn [_] (delay {:status 200 :body (java.io.StringReader. "{\"models\":[\"a\",\"b\"]}")}))]
    (is (m/validate p/LoadedResult (host/loaded (speaches/host "http://fixture"))))
    (is (= #{"a" "b"} (:ok (host/loaded (speaches/host "http://fixture")))))
    (is (m/validate p/LoadResult (host/load! (speaches/host "http://fixture") "a")))))

(deftest identity-preserves-positional-alignment
  (let [texts ["second" "first" "second"]
        out (tr/translate-batch (identity/make-translator) texts "en" "de" {})]
    (is (m/validate p/TranslateResult out))
    (is (= texts (:ok out)))))
