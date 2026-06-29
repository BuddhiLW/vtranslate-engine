(ns vtranslate.engine.calc.pipeline-test
  "Promote-layer property (segment indexing) + a golden snapshot of the full
   api/run-job pipeline through mock ports (record-free plain-data projection)."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [hive-test.golden :as golden]
            [hive-dsl.result :as r]
            [vtranslate.engine.api :as api]
            [vtranslate.engine.domain.job :as job]
            [vtranslate.engine.calc.transcription :as c.tx]
            [vtranslate.engine.adapters.segmenter.stub :as seg-stub]
            [vtranslate.engine.port.media :as p.media]
            [vtranslate.engine.port.transcriber :as p.asr]
            [vtranslate.engine.port.translator :as p.tr]
            [vtranslate.engine.port.subtitle :as p.sub]))

(def gen-seg
  (gen/fmap (fn [[s d t]] {:start-ms s :end-ms (+ s d) :text t :confidence 0.9})
            (gen/tuple (gen/choose 0 10000) (gen/choose 1 5000) gen/string-alphanumeric)))

;; PROPERTY: build-transcript assigns 1..n indices regardless of ASR ordering.
(defspec build-transcript-indices 100
  (prop/for-all [segs (gen/vector gen-seg 1 8)]
    (let [res (c.tx/build-transcript {:id "t" :asset-id "a" :language "en" :segments segs})]
      (and (r/ok? res)
           (= (range 1 (inc (count segs)))
              (map :index (:segments (:ok res))))))))

(def mock-ports
  {:media (reify
            p.media/IMediaProbe
            (probe [_ _] (r/ok {:container "mp4" :duration-ms 2000 :has-audio? true :audio-codec "aac"}))
            p.media/IAudioExtractor
            (extract-audio [_ _ _] (r/ok {:path "/tmp/a.wav"})))
   :transcriber (reify p.asr/ITranscriber
                  (transcribe [_ _ _ _]
                    (r/ok {:segments [{:start-ms 0 :end-ms 1000 :text "hello" :confidence 0.9}
                                      {:start-ms 1000 :end-ms 2000 :text "world" :confidence 0.8}]})))
   :translator (reify p.tr/ITranslator
                 (translate-batch [_ txts _ _ _] (r/ok (mapv #(str % "-pt") txts))))
   :renderer (reify p.sub/ISubtitleRenderer
               (render-bytes [_ t]
                 (r/ok (str/join "\n\n" (for [c (:cues t)]
                                          (str (:index c) "\n" (str/join "\n" (:lines c))))))))})

(defn- pipeline-projection []
  (let [res (api/run-job mock-ports {:job-id "j1" :source "/v.mp4"
                                     :source-language "en" :target-language "pt-BR"
                                     :format :format/srt})]
    {:ok?           (r/ok? res)
     :job-state     (job/describe (get-in res [:ok :job]))
     :transcript-id (get-in res [:ok :job :transcript-id])
     :subtitle-id   (get-in res [:ok :job :subtitle-id])
     :seg-indices   (mapv :index (get-in res [:ok :transcript :segments]))
     :cue-lines     (mapv :lines (get-in res [:ok :subtitle-track :cues]))
     :rendered      (get-in res [:ok :rendered])}))

;; GOLDEN: pipeline shape is stable (first run records test/golden/pipeline-shape.edn).
(golden/deftest-golden-fn pipeline-shape
  "test/golden/pipeline-shape.edn"
  pipeline-projection)

;; WIRING: cutting phase A — a present segmenter cuts spans (bounded by probed
;; duration) and they reach the transcriber as :spans opts; absent => nil.
(deftest segmenter-feeds-transcriber-spans
  (let [seen (atom ::unset)
        capturing (assoc mock-ports
                         :segmenter (seg-stub/make-segmenter 1000)
                         :transcriber (reify p.asr/ITranscriber
                                        (transcribe [_ _ _ opts]
                                          (reset! seen (:spans opts))
                                          (r/ok {:segments [{:start-ms 0 :end-ms 1000 :text "hi" :confidence 0.9}]}))))
        res (api/run-job capturing {:job-id "j" :source "/v.mp4"
                                    :source-language "en" :target-language "pt-BR"})]
    (is (r/ok? res))
    ;; probe duration-ms 2000, window 1000 => two contiguous spans
    (is (= [{:start-ms 0 :end-ms 1000} {:start-ms 1000 :end-ms 2000}] @seen))))

(deftest absent-segmenter-passes-nil-spans
  (let [seen (atom ::unset)
        ports (assoc mock-ports
                     :transcriber (reify p.asr/ITranscriber
                                    (transcribe [_ _ _ opts]
                                      (reset! seen (:spans opts))
                                      (r/ok {:segments [{:start-ms 0 :end-ms 1 :text "x" :confidence 0.9}]}))))
        res (api/run-job ports {:job-id "j" :source "/v.mp4"
                                :source-language "en" :target-language "pt-BR"})]
    (is (r/ok? res))
    (is (nil? @seen) "no segmenter => :spans nil (ASR-native timestamps)")))
