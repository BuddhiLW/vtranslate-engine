(ns vtranslate.engine.port.hooks-test
  "The var-based port helpers an AOT-compiled addon uses, and the composition of
   the transcriber call-opt hooks."
  (:require [clojure.test :refer [deftest is]]
            [hive-dsl.result :as r]
            [vtranslate.engine.port.transcriber :as p.asr]
            [vtranslate.engine.port.translator :as p.tr]))

(deftest translator-and-translate-round-trip
  (let [t (p.tr/translator (fn [texts s t _] (r/ok (mapv #(str s ">" t ":" %) texts))))]
    (is (satisfies? p.tr/ITranslator t))
    (is (= ["en>pt:a"] (:ok (p.tr/translate t ["a"] "en" "pt" {}))))))

(deftest transcriber-and-transcribe-round-trip
  (let [t (p.asr/transcriber (fn [audio language opts] (r/ok {:segments [[audio language (:k opts)]]})))]
    (is (satisfies? p.asr/ITranscriber t))
    (is (= [[:a "en" 1]] (:segments (:ok (p.asr/transcribe* t :a "en" {:k 1})))))))

(deftest routes-compose-with-the-decorator-nearest-the-adapter-first
  (let [trail  (atom [])
        route  (fn [tag] (fn [decode l] (r/let-ok [raw (decode l)] (swap! trail conj tag) (r/ok (conj raw tag)))))
        opts   (-> {} (p.asr/with-route (route :outer)) (p.asr/with-route (route :inner)))
        result ((:asr/route-window opts) (fn [l] (r/ok [l])) "en")]
    (is (= [:inner :outer] @trail))
    (is (= ["en" :inner :outer] (:ok result)))))

(deftest cleans-compose-with-the-decorator-nearest-the-adapter-first
  (let [opts (-> {} (p.asr/with-clean (fn [s _] (conj s :outer))) (p.asr/with-clean (fn [s _] (conj s :inner))))]
    (is (= [:inner :outer] ((:asr/clean opts) [] {})))))
