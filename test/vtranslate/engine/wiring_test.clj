(ns vtranslate.engine.wiring-test
  "DI composition root (OCP build-port multimethod). Covers the :default fail-loud,
   the :composer strategy (:none passthrough vs a resolved / unknown composer), and
   the bytedeco-free parse-ports assembly. No natives."
  (:require [clojure.test :refer [deftest is]]
            [hive-dsl.result :as r]
            [vtranslate.engine.wiring :as sut]
            [vtranslate.engine.adapters.translator.chunked :as chunked]
            [vtranslate.engine.providers.config :as cfg]))

(defn- temp-config [edn]
  (let [f (java.io.File/createTempFile "vtranslate-wiring" ".edn")]
    (spit f (pr-str edn))
    (.deleteOnExit f)
    (.getPath f)))

(deftest build-port-default-fails-loud
  (let [res (sut/build-port :totally-unknown-port {})]
    (is (r/err? res))
    (is (= :error/adapters-not-wired (:error res)))))

(deftest composer-none-is-passthrough
  (let [path (temp-config {:providers {:composer :none}})]
    (with-redefs [cfg/config-path (constantly path)]
      (let [res (sut/build-port :composer {})]
        (is (r/ok? res))
        (is (nil? (:ok res)) ":none => no muxer; the compose stage is a passthrough")))))

(deftest composer-unknown-fails-loud
  (let [path (temp-config {:providers {:composer :nonsense-composer}})]
    (with-redefs [cfg/config-path (constantly path)]
      (let [res (sut/build-port :composer {})]
        (is (r/err? res) "an unknown composer strategy fails loud via the registry")))))

(deftest parse-ports-assembles-bytedeco-free-set
  ;; load the bytedeco-free adapters so :source/:subtitle-parser/:renderer register
  (require 'vtranslate.engine.adapters.source.file
           'vtranslate.engine.adapters.codec.dispatch
           'vtranslate.engine.adapters.translator.identity)
  (let [path (temp-config {:providers {:translator :identity}})]
    (with-redefs [cfg/config-path (constantly path)]
      (let [res (sut/parse-ports {})]
        (is (r/ok? res))
        (is (= #{:source :parser :translator :renderer}
               (set (keys (:ok res)))))))))

(deftest translator-chunked-when-configured
  (require 'vtranslate.engine.adapters.translator.identity)
  (let [path (temp-config {:providers       {:translator :identity}
                           :translator-opts {:chunk-size 3}})]
    (with-redefs [cfg/config-path (constantly path)]
      (let [res (sut/build-port :translator {})]
        (is (r/ok? res))
        (is (instance? vtranslate.engine.adapters.translator.chunked.ChunkedTranslator
                       (:ok res))
            ":translator-opts :chunk-size => the router's translator is chunk-decorated")))))

(deftest translator-not-chunked-without-chunk-size
  (require 'vtranslate.engine.adapters.translator.identity)
  (let [path (temp-config {:providers       {:translator :identity}
                           :translator-opts {:window 40}})]
    (with-redefs [cfg/config-path (constantly path)]
      (let [res (sut/build-port :translator {})]
        (is (r/ok? res))
        (is (not (instance? vtranslate.engine.adapters.translator.chunked.ChunkedTranslator
                            (:ok res)))
            "no :chunk-size => translator passes through undecorated by chunked")))))

(deftest translator-chunked-resolves-fallback-provider
  (require 'vtranslate.engine.adapters.translator.identity)
  (let [path (temp-config {:providers       {:translator :identity}
                           :translator-opts {:chunk-size 3 :fallback-translator :identity}})]
    (with-redefs [cfg/config-path (constantly path)]
      (let [res (sut/build-port :translator {})]
        (is (r/ok? res))
        (is (some? (:fallback (:ok res)))
            ":fallback-translator names a provider key resolved as the per-chunk retry")))))

(deftest translator-chunked-unknown-fallback-fails-loud
  (require 'vtranslate.engine.adapters.translator.identity)
  (let [path (temp-config {:providers       {:translator :identity}
                           :translator-opts {:chunk-size 3 :fallback-translator :nonsense}})]
    (with-redefs [cfg/config-path (constantly path)]
      (let [res (sut/build-port :translator {})]
        (is (r/err? res) "an unknown :fallback-translator key fails loud via the registry")))))
