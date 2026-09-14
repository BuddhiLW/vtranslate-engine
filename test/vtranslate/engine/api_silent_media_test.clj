(ns vtranslate.engine.api-silent-media-test
  "Media with no speech in it: the job FINISHES, carrying an empty subtitle and
   the :silent? flag, instead of dying with :error/asr-failed \"no segments
   produced\". Measured case: Big Buck Bunny, ten minutes of music and effects,
   where the old behaviour spent the whole decode and then failed the job.
   Mock ports, no IO."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [hive-dsl.result :as r]
            [vtranslate.engine.api :as api]
            [vtranslate.engine.domain.transcription :as tx]
            [vtranslate.engine.port.media :as p.media]
            [vtranslate.engine.port.transcriber :as p.asr]
            [vtranslate.engine.port.translator :as p.tr]
            [vtranslate.engine.port.subtitle :as p.sub]))

(defn- ports
  "Mock ports whose transcriber hears `segments` — [] is the silent film."
  [segments translated]
  {:media (reify
            p.media/IMediaProbe
            (probe [_ _] (r/ok {:container "mp4" :duration-ms 596480 :has-audio? true
                                :audio-codec "aac"}))
            p.media/IAudioExtractor
            (extract-audio [_ _ _] (r/ok {:path "/tmp/a.wav"})))
   :transcriber (reify p.asr/ITranscriber
                  (transcribe [_ _ _ _] (r/ok {:segments segments})))
   :translator (reify p.tr/ITranslator
                 (translate-batch [_ txts _ _ _]
                   (swap! translated conj txts)
                   (r/ok (mapv #(str % "-pt") txts))))
   :renderer (reify p.sub/ISubtitleRenderer
               (render-bytes [_ t]
                 (r/ok (str/join "\n\n" (for [c (:cues t)]
                                          (str (:index c) "\n" (str/join "\n" (:lines c))))))))})

(defn- run [segments translated]
  (api/run-job (assoc (ports segments translated) :config {})
               {:job-id "j" :source "/v.mp4" :source-language "en"
                :target-language "pt-BR" :format :format/srt}))

(deftest a-film-with-no-speech-is-a-finished-job
  (let [translated (atom [])
        res        (run [] translated)]
    (is (r/ok? res) "no speech is an answer, not an ASR failure")
    (testing "and it says so, rather than looking like an empty run"
      (is (true? (get-in res [:ok :silent?])))
      (is (= "" (get-in res [:ok :rendered])) "an empty subtitle, not a missing one")
      (is (= [] (get-in res [:ok :outputs])))
      (is (tx/silent? (get-in res [:ok :transcript]))))
    (testing "the job reaches its terminal state"
      (is (= :job/completed (:adt/variant (get-in res [:ok :job :state])))))
    (testing "and nothing was sent to the translator"
      (is (empty? @translated)))))

(deftest speech-still-travels-the-whole-pipeline
  (let [translated (atom [])
        res        (run [{:start-ms 0 :end-ms 1000 :text "hello" :confidence 0.9}] translated)]
    (is (r/ok? res))
    (is (nil? (get-in res [:ok :silent?])))
    (is (not (tx/silent? (get-in res [:ok :transcript]))))
    (is (= 1 (count (get-in res [:ok :outputs]))))
    (is (str/includes? (get-in res [:ok :rendered]) "hello-pt"))
    (is (= [["hello"]] @translated))))
