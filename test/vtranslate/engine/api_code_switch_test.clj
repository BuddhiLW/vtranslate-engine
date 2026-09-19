(ns vtranslate.engine.api-code-switch-test
  "A transcript whose speakers switch language mid-stream: every language is
   translated from its own source language, a segment marked :segment/verbatim?
   (as an addon marks a decoder placeholder) is never sent to the translator and
   keeps its text, and speech already in the target language is carried over
   verbatim. Mock ports, no IO."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [hive-dsl.result :as r]
            [vtranslate.engine.api :as api]
            [vtranslate.engine.port.media :as p.media]
            [vtranslate.engine.port.transcriber :as p.asr]
            [vtranslate.engine.port.translator :as p.tr]
            [vtranslate.engine.port.subtitle :as p.sub]))

(def ^:private segments
  [{:start-ms 0    :end-ms 1000 :text "einer der berühmtesten Reden" :language "de" :confidence 1.0}
   {:start-ms 1000 :end-ms 2000 :text "All free men, wherever they may live" :language "en" :confidence 1.0}
   {:start-ms 2000 :end-ms 3000 :text "(speaking foreign language)" :language "en" :confidence 1.0
    :segment/verbatim? true}
   {:start-ms 3000 :end-ms 4000 :text "Das hat die Menschen beeindruckt" :language "de" :confidence 1.0}
   {:start-ms 4000 :end-ms 5000 :text "Eu sou um berlinense" :language "pt" :confidence 1.0}])

(defn- ports
  "Mock ports; the translator records every call as [source-language texts]."
  [calls]
  {:media (reify
            p.media/IMediaProbe
            (probe [_ _] (r/ok {:container "mp4" :duration-ms 5000 :has-audio? true :audio-codec "aac"}))
            p.media/IAudioExtractor
            (extract-audio [_ _ _] (r/ok {:path "/tmp/a.wav"})))
   :transcriber (reify p.asr/ITranscriber
                  (transcribe [_ _ _ _] (r/ok {:segments segments})))
   :translator (reify p.tr/ITranslator
                 (translate-batch [_ txts source target _]
                   (swap! calls conj [source (vec txts)])
                   (r/ok (mapv #(str target ":" %) txts))))
   :renderer (reify p.sub/ISubtitleRenderer
               (render-bytes [_ t]
                 (r/ok (str/join "\n\n" (for [c (:cues t)] (str/join "\n" (:lines c)))))))})

(defn- run [target]
  (let [calls (atom [])
        res   (api/run-job (assoc (ports calls) :config {})
                           {:job-id "j" :source "/v.mp4" :source-language "auto"
                            :target-language target :format :format/srt})]
    {:res res :calls @calls}))

(deftest each-source-language-is-translated-from-itself
  (let [{:keys [res calls]} (run "fr")]
    (is (r/ok? res))
    (is (= {"de" ["einer der berühmtesten Reden" "Das hat die Menschen beeindruckt"]
            "en" ["All free men, wherever they may live"]
            "pt" ["Eu sou um berlinense"]}
           (into {} calls))
        "one batch per source language, each carrying its own source tag")))

(deftest a-placeholder-never-reaches-the-translator-and-keeps-its-text
  (let [{:keys [res calls]} (run "fr")
        units (get-in res [:ok :translated :units])]
    (is (not-any? #(some #{"(speaking foreign language)"} %) (map second calls)))
    (is (= "(speaking foreign language)" (:target-text (nth units 2)))
        "nothing is deleted: the cue survives, untranslated")
    (is (= "fr:All free men, wherever they may live" (:target-text (nth units 1))))))

(deftest speech-already-in-the-target-language-is-carried-verbatim
  (let [{:keys [res calls]} (run "pt")
        units (get-in res [:ok :translated :units])]
    (is (r/ok? res))
    (is (not (contains? (into {} calls) "pt")) "no pt->pt request is made")
    (is (= "Eu sou um berlinense" (:target-text (nth units 4))))
    (testing "the other languages still translate"
      (is (= "pt:Das hat die Menschen beeindruckt" (:target-text (nth units 3)))))))
