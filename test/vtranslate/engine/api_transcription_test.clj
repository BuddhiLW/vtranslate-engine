(ns vtranslate.engine.api-transcription-test
  "ASR-only ingress (api/run-transcription-job): ingest + transcribe, with no
   translation, render, or compose stage. Covers the happy path, the
   same-source-and-target case the translation ingress rejects, and fail-loud
   propagation of an ASR error."
  (:require [clojure.test :refer [deftest is]]
            [hive-dsl.result :as r]
            [vtranslate.engine.api :as api]
            [vtranslate.engine.port.media :as p.media]
            [vtranslate.engine.port.transcriber :as p.asr]))

(defn- mock-ports
  ([] (mock-ports (reify p.asr/ITranscriber
                    (transcribe [_ _ _ _]
                      (r/ok {:segments [{:start-ms 0 :end-ms 1000
                                         :text "hello" :confidence 0.9}
                                        {:start-ms 1000 :end-ms 2000
                                         :text "world" :confidence 0.8}]})))))
  ([transcriber]
   {:media (reify
             p.media/IMediaProbe
             (probe [_ _] (r/ok {:container "mp4" :duration-ms 2000
                                 :has-audio? true :audio-codec "aac"}))
             p.media/IAudioExtractor
             (extract-audio [_ _ _] (r/ok {:path "/tmp/a.wav"})))
    :transcriber transcriber}))

(deftest transcription-job-completes-without-translation
  (let [res (api/run-transcription-job (mock-ports)
                                       {:job-id "t1" :source "/v.mp4"
                                        :source-language "en"})]
    (is (r/ok? res))
    (is (= :job/completed (get-in res [:ok :job :state :adt/variant])))
    (is (= "t1-tx" (get-in res [:ok :transcript :id])))
    (is (= ["hello" "world"] (mapv :text (get-in res [:ok :transcript :segments]))))
    (is (= "t1-tx" (get-in res [:ok :job :transcript-id]))
        "the job references its transcript by id")
    (is (nil? (get-in res [:ok :translated])) "no translation stage runs")
    (is (nil? (get-in res [:ok :rendered])) "no render stage runs")))

(deftest transcription-job-records-undetermined-target-language
  (let [res (api/run-transcription-job (mock-ports)
                                       {:job-id "t2" :source "/v.mp4"
                                        :source-language "en"})]
    (is (r/ok? res))
    (is (= api/transcription-target-language
           (get-in res [:ok :job :target-language])))
    (is (= "en" (get-in res [:ok :transcript :language]))
        "an explicit source language is carried onto the transcript")))

(deftest transcription-job-defaults-language-to-und
  (let [res (api/run-transcription-job (mock-ports)
                                       {:job-id "t3" :source "/v.mp4"
                                        :source-language "auto"})]
    (is (r/ok? res))
    (is (= "und" (get-in res [:ok :transcript :language])))))

(deftest transcription-job-propagates-asr-failure
  (let [failing (reify p.asr/ITranscriber
                  (transcribe [_ _ _ _]
                    (r/err :error/asr-failed {:reason "backend down"})))
        res     (api/run-transcription-job (mock-ports failing)
                                           {:job-id "t4" :source "/v.mp4"
                                            :source-language "en"})]
    (is (r/err? res))
    (is (= :error/asr-failed (:error res)))))
