(ns vtranslate.engine.adapters.openai-trifecta-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [aleph.http :as http]
            [vtranslate.engine.schema.port-values :as p]
            [vtranslate.engine.adapters.transcriber.openai-compatible :as o]
            [vtranslate.engine.adapters.transcriber.support :as sup]
            [vtranslate.engine.port.transcriber :as asr]))
(defn multipart-observation [[fields filename content :as in]]
  {:in in :out (o/multipart fields {:name "file" :filename filename :bytes content})})
(deftrifecta multipart multipart-observation
  {:golden-path "test/golden/adapters-openai-multipart.edn"
   :cases {:empty [{} "a.wav" "bytes"] :fields [{"model" "whisper" "language" "he"} "b.wav" "pcm"]}
   :gen (gen/tuple (gen/map gen/string-alphanumeric gen/string-alphanumeric {:max-elements 8})
                   gen/string-alphanumeric gen/string-alphanumeric)
   :pred (fn [{[fields filename content] :in out :out}]
           (and (m/validate [:vector [:map [:part-name :string] [:content :string]]] out)
                (= fields (into {} (map (juxt :part-name :content) (pop out))))
                (= {:part-name "file" :file-name filename :mime-type "audio/wav" :content content} (peek out))))})
(defn segments-observation [[start duration text :as in]]
  {:in in :out (o/segments-from {:segments [{:start (/ start 1000.0) :end (/ (+ start duration) 1000.0) :text text}]}
                              nil {})})
(deftrifecta segments-from segments-observation
  {:golden-path "test/golden/adapters-openai-segments.edn"
   :cases {:speech [100 900 "hello"] :unicode [0 100 "שלום"]}
   :gen (gen/tuple (gen/choose 0 100000) (gen/choose 1 10000) (gen/fmap #(str "speech" %) gen/string-alphanumeric))
   :pred (fn [{[start duration text] :in out :out}]
           (and (m/validate p/Segments out) (= [start (+ start duration) text]
                                                              ((juxt :start-ms :end-ms :text) (first out)))))})
(deftest offline-openai-port
  (with-redefs-fn {#'o/read-all-bytes (fn [_] {:ok (byte-array 44)})
                  #'sup/wav-duration-ms (constantly 100)
                  #'http/post (fn [& _] (delay {:status 200 :body (java.io.StringReader. "{\"segments\":[{\"start\":0,\"end\":0.1,\"text\":\"fixture\"}]}" )}))}
    (fn []
      (let [result (asr/transcribe (o/->OpenAiTranscriber "http://fixture" "test" nil {}) "fixture.wav" "en" {})]
        (is (m/validate p/TranscribeResult result))
        (is (= ["fixture"] (mapv :text (get-in result [:ok :segments]))))))))
