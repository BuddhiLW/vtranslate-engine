(ns vtranslate.engine.pipeline.extensions-test
  "The generic pre-translate extension seam: a registered middleware's :translate/opts
   reach the translator and its :result/extra merge into the job result — the engine
   folds middleware without naming what they do. The middleware is registered only for
   this ns (a :once fixture removes it), so the default (no middleware) stays intact."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [hive-dsl.result :as r]
            [vtranslate.engine.api :as api]
            [vtranslate.engine.pipeline.extensions :as ext]
            [vtranslate.engine.port.media :as p.media]
            [vtranslate.engine.port.transcriber :as p.asr]
            [vtranslate.engine.port.translator :as p.tr]
            [vtranslate.engine.port.subtitle :as p.sub]))

(defn- tag-mw [_resources ctx]
  (r/ok (assoc ctx
               :translate/opts {:prompt/system-suffix "SUFFIX-X"}
               :result/extra   {:probe/ran true})))

(use-fixtures :once
  (fn [f]
    (.addMethod ^clojure.lang.MultiFn ext/middleware
                :vtranslate.pipeline/pre-translate (fn [_ _] [tag-mw]))
    (try (f)
         (finally (remove-method ext/middleware :vtranslate.pipeline/pre-translate)))))

(def ^:private captured (atom nil))

(def ^:private mock-ports
  {:media (reify
            p.media/IMediaProbe (probe [_ _] (r/ok {:container "mp4" :duration-ms 1000 :has-audio? true :audio-codec "aac"}))
            p.media/IAudioExtractor (extract-audio [_ _ _] (r/ok {:path "/tmp/a.wav"})))
   :transcriber (reify p.asr/ITranscriber
                  (transcribe [_ _ _ _] (r/ok {:segments [{:start-ms 0 :end-ms 1000 :text "hello" :confidence 0.9}]})))
   :translator (reify p.tr/ITranslator
                 (translate-batch [_ txts _ _ opts] (reset! captured opts) (r/ok (mapv #(str % "-pt") txts))))
   :renderer (reify p.sub/ISubtitleRenderer (render-bytes [_ _] (r/ok "sub")))})

(deftest middleware-opts-reach-translator-and-result-merges
  (reset! captured nil)
  (let [res (api/run-job mock-ports {:job-id "j" :source "/v.mp4"
                                     :source-language "en" :target-language "pt-BR"})]
    (is (r/ok? res))
    (is (= "SUFFIX-X" (:prompt/system-suffix @captured))
        "middleware :translate/opts merged into every translate-batch opts")
    (is (= [0] (:segment-indices @captured)) "core batch opts still present alongside")
    (is (true? (get-in res [:ok :probe/ran]))
        "middleware :result/extra merged into the job result")))

(deftest middleware-error-halts-the-pipeline
  (reset! captured nil)
  (.addMethod ^clojure.lang.MultiFn ext/middleware :vtranslate.pipeline/pre-translate
              (fn [_ _] [(fn [_ _] (r/err :error/extension-boom {:why "test"}))]))
  (try
    (let [res (api/run-job mock-ports {:job-id "j2" :source "/v.mp4"
                                       :source-language "en" :target-language "pt-BR"})]
      (is (r/err? res) "a middleware r/err halts the pipeline")
      (is (= :error/extension-boom (:error res)))
      (is (nil? @captured) "translation never ran after the halting middleware"))
    (finally
      (.addMethod ^clojure.lang.MultiFn ext/middleware
                  :vtranslate.pipeline/pre-translate (fn [_ _] [tag-mw])))))

(deftest default-phase-has-no-middleware
  (is (= [] (ext/middleware :vtranslate.pipeline/no-such-phase {}))))
