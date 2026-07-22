(ns vtranslate.engine.providers.config-test
  (:require [clojure.test :refer [deftest is]]
            [hive-dsl.result :as r]
            [vtranslate.engine.providers.config :as sut]
            [vtranslate.engine.wiring :as wiring]))

(defn- temp-config [edn]
  (let [f (java.io.File/createTempFile "vtranslate-config" ".edn")]
    (spit f (pr-str edn))
    (.deleteOnExit f)
    (.getPath f)))

(deftest resolve-routing-has-no-implicit-translator
  (let [path (temp-config {})]
    (with-redefs [sut/config-path (constantly path)]
      (let [res (sut/resolve-routing {})]
        (is (r/ok? res))
        (is (nil? (get-in res [:ok :translator])))))))

;; --- precedence: (caller > env >) file > default -----------------------------

(deftest resolve-routing-applies-defaults-when-absent
  ;; empty config + no VT_* env => the built-in selector defaults apply.
  (let [path (temp-config {})]
    (with-redefs [sut/config-path (constantly path)]
      (let [res (sut/resolve-routing {})]
        (is (r/ok? res))
        (is (= :grid (get-in res [:ok :segmenter])) "segmenter falls back to :grid")
        (is (= :none (get-in res [:ok :composer])) "composer falls back to :none")))))

(deftest resolve-routing-file-selector-beats-default
  ;; a config.edn selector wins over the built-in default (the file rung).
  (let [path (temp-config {:providers {:composer :hard :segmenter :silero-vad}})]
    (with-redefs [sut/config-path (constantly path)]
      (let [res (sut/resolve-routing {})]
        (is (r/ok? res))
        (is (= :hard (get-in res [:ok :composer])) "file selector beats the :none default")
        (is (= :silero-vad (get-in res [:ok :segmenter])) "file selector beats the :grid default")))))

(deftest resolve-routing-loads-provider-opts-from-file
  (let [path (temp-config {:providers {:segmenter :silero-vad
                                       :transcriber :whisper-local
                                       :translator :venice}
                           :addons [{:ns 'vtranslate.context.addon
                                     :config {:collection-id "movies"}}]
                           :segmenter-opts {:model-path "models/silero_vad.onnx"}
                           :transcriber-opts {:model-path "models/ggml-large-v3.bin"
                                              :span-pad-ms 750}
                           :translator-opts {:api-url "https://custom.venice/chat/completions"
                                             :model "venice-model"
                                             :secret-pass "Venice/api-key"}})]
    (with-redefs [sut/config-path (constantly path)]
      (let [res (sut/resolve-routing {})]
        (is (r/ok? res))
        (is (= :silero-vad (get-in res [:ok :segmenter])))
        (is (= :whisper-local (get-in res [:ok :transcriber])))
        (is (= :venice (get-in res [:ok :translator])))
        (is (= [{:ns 'vtranslate.context.addon
                 :config {:collection-id "movies"}}]
               (get-in res [:ok :addons])))
        (is (= "models/silero_vad.onnx" (get-in res [:ok :segmenter-opts :model-path])))
        (is (= 750 (get-in res [:ok :transcriber-opts :span-pad-ms])))
        (is (= "Venice/api-key" (get-in res [:ok :translator-opts :secret-pass])))))))

(deftest resolve-routing-overrides-provider-opts
  (let [path (temp-config {:providers {:translator :openrouter}
                           :translator-opts {:model "file-model"
                                             :secret-pass "openrouter/file"}})]
    (with-redefs [sut/config-path (constantly path)]
      (let [res (sut/resolve-routing {:translator :venice
                                      :translator-opts {:model "override-model"
                                                        :secret-pass "Venice/api-key"}})]
        (is (r/ok? res))
        (is (= :venice (get-in res [:ok :translator])))
        (is (= {:model "override-model" :secret-pass "Venice/api-key"}
               (get-in res [:ok :translator-opts])))))))

(deftest wiring-builds-translator-with-file-opts
  (require 'vtranslate.engine.adapters.translator.llm)
  (let [path (temp-config {:providers {:translator :venice}
                           :translator-opts {:api-url "https://custom.venice/chat/completions"
                                             :model "venice-model"
                                             :secret-pass "Venice/api-key"}})]
    (with-redefs [sut/config-path (constantly path)]
      (let [res (wiring/build-port :translator {})]
        (is (r/ok? res))
        (is (= "https://custom.venice/chat/completions" (:api-url (:ok res))))
        (is (= "venice-model" (:model (:ok res))))
        (is (= "Venice/api-key" (:secret-pass (:ok res))))))))