(ns vtranslate.engine.api
  (:require [hive-dsl.result :as r]
            [vtranslate.engine.domain.job :as job]
            [vtranslate.engine.domain.ingestion :as ing]
            [vtranslate.engine.calc.transcription :as c.tx]
            [vtranslate.engine.calc.translation :as c.tr]
            [vtranslate.engine.calc.rendering :as c.rd]
            [vtranslate.engine.calc.reflow :as c.reflow]
            [vtranslate.engine.calc.subtitle-in :as c.si]
            [vtranslate.engine.calc.subtitle-out :as c.so]
            [vtranslate.engine.calc.batching :as batch]
            [vtranslate.engine.port.media :as p.media]
            [vtranslate.engine.port.segmenter :as p.seg]
            [vtranslate.engine.port.transcriber :as p.asr]
            [vtranslate.engine.port.translator :as p.tr]
            [vtranslate.engine.port.subtitle :as p.sub]
            [vtranslate.engine.port.source :as p.src]
            [vtranslate.engine.pipeline.fsm :as pf]
            [vtranslate.engine.shared :as shared]
            [vtranslate.engine.port.composer :as p.comp]
            [vtranslate.engine.pipeline.extensions :as ext]
            [vtranslate.engine.adapters.translator.augment :as augment]))

;; ---------------------------------------------------------------------------
;; Language helpers
;; ---------------------------------------------------------------------------

(defn- segment-audio [segmenter audio probe]
  (if segmenter
    (r/let-ok [out (p.seg/segment segmenter audio {:duration-ms (:duration-ms probe)})]
              (r/ok (:spans out)))
    (r/ok nil)))

(defn- auto-language? [language]
  (contains? #{nil "" "auto" "multi" "und"} language))

(defn- explicit-source-language [language]
  (when-not (auto-language? language) language))

(defn- transcript-language [language]
  (if (auto-language? language) "und" language))

;; ---------------------------------------------------------------------------
;; Shared pipeline seams — both the video and subtitle pipelines reuse these
;; ---------------------------------------------------------------------------

(defn- start-job
  "Shared start seam: run `validate` on `spec`, then build the media asset (as `kind`)
   and a pending TranslationJob. => an fsm result-state carrying {:spec :asset :job}."
  [{:keys [job-id source target-language] :as spec} kind validate]
  (pf/result-state
   (r/let-ok [_     (validate spec)
              asset (ing/make-media-asset
                     {:id (str job-id "-asset") :source-uri source :kind kind})
              job   (job/make-translation-job
                     {:id job-id :asset-id (:id asset) :target-language target-language})]
             (r/ok {:spec spec :asset asset :job job}))))

(defn- finalize-job
  "Shared finalize seam: advance the job, apply the terminal `step` transition
   (advance | complete), then link the produced subtitle track by id. => Result<Job>."
  [job track step]
  (r/let-ok [advanced (job/advance job)
             done     (step advanced)]
            (r/ok (job/link-subtitle done (:id track)))))

;; ---------------------------------------------------------------------------
;; Video pipeline stages
;; ---------------------------------------------------------------------------

(defn- start-translation [_ {:keys [asset-kind] :as spec}]
  (start-job spec asset-kind (constantly (r/ok nil))))

(defn- ingest-media [{:keys [media]} state]
  (pf/with-result
    state
    (fn [{:keys [spec asset job] :as ctx}]
      (let [{:keys [source]} spec]
        (r/let-ok [probe (p.media/probe media source)
                   ready (ing/ready (ing/with-probe asset probe))
                   job   (job/advance job)
                   audio (p.media/extract-audio media source {})]
                  (r/ok (assoc ctx
                               :asset ready
                               :job job
                               :probe probe
                               :audio audio)))))))

(defn- transcribe-media [{:keys [segmenter transcriber]} state]
  (pf/with-result
    state
    (fn [{:keys [spec asset job probe audio] :as ctx}]
      (let [{:keys [job-id source-language]} spec]
        (r/let-ok [spans      (segment-audio segmenter audio probe)
                   asr        (p.asr/transcribe transcriber audio
                                                (explicit-source-language source-language)
                                                {:spans spans})
                   transcript (c.tx/build-transcript
                               {:id (str job-id "-tx") :asset-id (:id asset)
                                :language (transcript-language source-language)
                                :segments (:segments asr)})
                   job        (job/advance job)]
                  (r/ok (assoc ctx
                               :job (job/link-transcript job (:id transcript))
                               :transcript transcript)))))))

(defn- apply-extensions
  "OCP extension point: fold every registered pre-translate middleware over the
   pipeline context (r/let-ok short-circuit). Middleware augment ctx (e.g. add
   :translate/opts for the translator or :result/extra for the job result); with no
   addon loaded there is no middleware and this is a no-op."
  [resources state]
  (pf/with-result
    state
    (fn [ctx]
      (reduce (fn [acc mw] (r/let-ok [c acc] (mw resources c)))
              (r/ok ctx)
              (ext/middleware :vtranslate.pipeline/pre-translate resources)))))

(defn- translate-indexed-group [translator target-language [source-language indexed-segments]]
  (let [{:keys [indices values]} (batch/group-payload indexed-segments :text)]
    (r/let-ok [targets (p.tr/translate-batch translator values source-language target-language
                                             {:segment-indices indices})]
      (batch/zip-indices indices targets
                         (fn [expected actual]
                           (c.tr/translation-count-error source-language expected actual))))))

(defn- collect-translations [translator target-language groups]
  (reduce (fn [acc group]
            (r/let-ok [pairs acc
                       group-pairs (translate-indexed-group translator target-language group)]
              (r/ok (into pairs group-pairs))))
          (r/ok [])
          groups))

(defn- translate-segments [translator transcript target-language fallback-source-language]
  (let [segments (:segments transcript)
        groups   (batch/index-groups
                  segments
                  #(c.tr/segment-source-language transcript fallback-source-language %))]
    (r/let-ok [pairs (collect-translations translator target-language groups)]
      (batch/scatter (count segments) pairs
                     (fn [expected actual]
                       (c.tr/translation-count-error "ordered-translations" expected actual))))))

(defn- translate-transcript [{:keys [translator]} state]
  (pf/with-result
    state
    (fn [{:keys [spec job transcript] :as ctx}]
      (let [{:keys [job-id source-language target-language]} spec
            tr (augment/wrap-opts translator (:translate/opts ctx))]
        (r/let-ok [targets    (translate-segments tr transcript target-language
                                                  (explicit-source-language source-language))
                   translated (c.tr/build-translated-cues
                               transcript targets
                               {:id (str job-id "-tc") :target-language target-language})
                   job        (job/advance job)]
                  (r/ok (assoc ctx
                               :job job
                               :translated translated)))))))

(defn- build-render-track [{:keys [job-id format]} translated]
  (c.rd/build-subtitle-track translated {:id (str job-id "-sub") :format format}))

(defn- render-subtitles [{:keys [renderer]} state]
  (pf/with-result
    state
    (fn [{:keys [spec job transcript translated] :as ctx}]
      (r/let-ok [track    (build-render-track spec translated)
                 rendered (p.sub/render-bytes renderer track)
                 job      (finalize-job job track job/advance)]
                (r/ok (merge {:spec spec
                              :job job
                              :transcript transcript
                              :translated translated
                              :subtitle-track track
                              :rendered rendered}
                             (:result/extra ctx)))))))

(defn- compose-video [{:keys [muxer]} state]
  (pf/with-result
    state
    (fn [{:keys [spec subtitle-track] :as ctx}]
      (if muxer
        (r/let-ok [out (p.comp/compose muxer (:source spec) subtitle-track
                                       {:output-uri (:output spec)})]
                  (r/ok (assoc ctx :output-video (:output-uri out))))
        (r/ok ctx)))))

(def ^:private video-fsm
  (pf/compile-stages
   [(pf/stage pf/start-id start-translation)
    (pf/stage :vtranslate.pipeline/ingest ingest-media)
    (pf/stage :vtranslate.pipeline/transcribe transcribe-media)
    (pf/stage :vtranslate.pipeline/extend apply-extensions)
    (pf/stage :vtranslate.pipeline/translate translate-transcript)
    (pf/stage :vtranslate.pipeline/render render-subtitles)
    (pf/stage :vtranslate.pipeline/compose compose-video)]))

(defn run-job
  [{:keys [media segmenter transcriber translator renderer muxer config]}
   {:keys [job-id source source-language target-language asset-kind format output]
    :or   {asset-kind :media/video format :format/srt}}]
  (pf/run-pipeline
   (pf/pipeline {:media media
                 :segmenter segmenter
                 :transcriber transcriber
                 :translator translator
                 :renderer renderer
                 :muxer muxer
                 :config config}
                video-fsm)
   {:job-id job-id
    :source source
    :source-language source-language
    :target-language target-language
    :asset-kind asset-kind
    :format format
    :output output}))

;; ---------------------------------------------------------------------------
;; Subtitle pipeline stages
;; ---------------------------------------------------------------------------

(defn- start-subtitle-translation [_ {:keys [source-language] :as spec}]
  (start-job spec :media/subtitle
             (fn [_]
               (if-let [src (explicit-source-language source-language)]
                 (shared/make-language src)
                 (r/ok nil)))))

(defn- read-subtitle-source [{:keys [reader]} state]
  (pf/with-result
    state
    (fn [{:keys [spec] :as ctx}]
      (r/let-ok [text (p.src/read-text reader (:source spec))]
                (r/ok (assoc ctx :text text))))))

(defn- non-empty-cues [cues]
  (if (seq cues)
    (r/ok :non-empty)
    (r/err :error/render-failed {:reason "no cues parsed from source"})))

(defn- parse-subtitle-source [{:keys [parser]} state]
  (pf/with-result
    state
    (fn [{:keys [spec text] :as ctx}]
      (let [{:keys [format reflow]} spec]
        (r/let-ok [parsed (p.sub/parse parser text format)
                   cues   (r/ok (let [cs (:cues parsed)]
                                  (if reflow (c.reflow/reflow cs reflow) cs)))
                   _      (non-empty-cues cues)]
                  (r/ok (assoc ctx :cues cues)))))))

(defn- translate-subtitle-cues [{:keys [translator]} state]
  (pf/with-result
    state
    (fn [{:keys [spec cues] :as ctx}]
      (let [{:keys [source-language target-language]} spec]
        (r/let-ok [texts   (r/ok (c.so/cue-texts cues))
                   targets (p.tr/translate-batch translator texts
                                                 source-language target-language {})
                   tcues   (c.so/apply-translations cues targets)]
                  (r/ok (assoc ctx :translated-cues tcues)))))))

(defn- render-subtitle-track [{:keys [renderer]} state]
  (pf/with-result
    state
    (fn [{:keys [spec asset job translated-cues]}]
      (let [{:keys [job-id target-language format]} spec]
        (r/let-ok [track    (c.si/build-subtitle-track
                              translated-cues {:id (str job-id "-sub")
                                               :source-id (:id asset)
                                               :language target-language
                                               :format format})
                   job      (finalize-job job track job/complete)
                   rendered (p.sub/render-bytes renderer track)]
                  (r/ok {:job job :subtitle-track track :rendered rendered}))))))

(def ^:private subtitle-fsm
  (pf/compile-stages
   [(pf/stage pf/start-id start-subtitle-translation)
    (pf/stage :vtranslate.subtitle/read read-subtitle-source)
    (pf/stage :vtranslate.subtitle/parse parse-subtitle-source)
    (pf/stage :vtranslate.subtitle/translate translate-subtitle-cues)
    (pf/stage :vtranslate.subtitle/render render-subtitle-track)]))

(defn run-subtitle-job
  [{:keys [parser translator renderer] reader :source}
   {:keys [job-id source source-language target-language format reflow]
    :or   {format :format/srt}}]
  (pf/run-pipeline
   (pf/pipeline {:reader reader
                 :parser parser
                 :translator translator
                 :renderer renderer}
                subtitle-fsm)
   {:job-id job-id
    :source source
    :source-language source-language
    :target-language target-language
    :format format
    :reflow reflow}))
