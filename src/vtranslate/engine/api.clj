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
            [vtranslate.engine.port.media :as p.media]
            [vtranslate.engine.port.segmenter :as p.seg]
            [vtranslate.engine.port.transcriber :as p.asr]
            [vtranslate.engine.port.translator :as p.tr]
            [vtranslate.engine.port.subtitle :as p.sub]
            [vtranslate.engine.port.source :as p.src]
            [hive.events.fsm :as fsm]))

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

(defprotocol ITranslationPipeline
  (run-translation-job [pipeline spec]))

(defprotocol IJobStage
  (stage-id [stage])
  (apply-stage [stage resources state]))

(defrecord JobStage [id handler]
  IJobStage
  (stage-id [_] id)
  (apply-stage [_ resources state]
    (handler resources state)))

(defn- result-state [result]
  {:result result})

(defn- result-of [state]
  (:result state))

(defn- continue? [state]
  (r/ok? (result-of state)))

(defn- halted? [state]
  (r/err? (result-of state)))

(defn- with-result [state f]
  (result-state
   (r/let-ok [ctx (result-of state)]
             (f ctx))))

(defn- step-dispatches [next-id]
  [[next-id continue?]
   [::fsm/end halted?]])

(defn- stage-state [stage next-id]
  [(stage-id stage)
   {:handler (fn [resources state]
               (apply-stage stage resources state))
    :dispatches (if next-id
                  (step-dispatches next-id)
                  [[::fsm/end (constantly true)]])}])

(defn- compile-stage-fsm [stages]
  (let [ids      (mapv stage-id stages)
        next-ids (conj (subvec ids 1) nil)]
    (fsm/compile
     {:fsm (into {} (map stage-state stages next-ids))})))

(defn- start-translation [_ {:keys [job-id source target-language asset-kind] :as spec}]
  (result-state
   (r/let-ok [asset (ing/make-media-asset
                     {:id (str job-id "-asset") :source-uri source :kind asset-kind})
              job   (job/make-translation-job
                     {:id job-id :asset-id (:id asset) :target-language target-language})]
             (r/ok {:spec spec :asset asset :job job}))))

(defn- ingest-media [{:keys [media]} state]
  (with-result
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
  (with-result
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

(defn- segment-source-language [transcript fallback-source-language segment]
  (or (:language segment) fallback-source-language (:language transcript) "und"))

(defn- indexed-segment-groups [transcript fallback-source-language]
  (group-by (fn [[_ segment]]
              (segment-source-language transcript fallback-source-language segment))
            (map-indexed vector (:segments transcript))))

(defn- translation-count-error [id expected actual]
  (r/err :error/translation-failed
         {:segment-id (str id)
          :reason (format "translation count %d != segment count %d" actual expected)}))

(defn- translate-indexed-group [translator target-language [source-language indexed-segments]]
  (let [indices (mapv first indexed-segments)
        texts   (mapv (comp :text second) indexed-segments)]
    (r/let-ok [targets (p.tr/translate-batch translator texts source-language target-language
                                             {:segment-indices indices})]
      (if (= (count targets) (count indices))
        (r/ok (mapv vector indices targets))
        (translation-count-error source-language (count indices) (count targets))))))

(defn- collect-translations [translator target-language groups]
  (reduce (fn [acc group]
            (r/let-ok [pairs acc
                       group-pairs (translate-indexed-group translator target-language group)]
              (r/ok (into pairs group-pairs))))
          (r/ok [])
          groups))

(defn- ordered-translations [segment-count indexed-translations]
  (let [missing ::missing-translation
        targets (reduce (fn [acc [index text]] (assoc acc index text))
                        (vec (repeat segment-count missing))
                        indexed-translations)]
    (if (some #{missing} targets)
      (translation-count-error "ordered-translations" segment-count (count (remove #{missing} targets)))
      (r/ok targets))))

(defn- translate-segments [translator transcript target-language fallback-source-language]
  (let [groups (indexed-segment-groups transcript fallback-source-language)]
    (r/let-ok [pairs (collect-translations translator target-language groups)]
      (ordered-translations (count (:segments transcript)) pairs))))


(defn- translate-transcript [{:keys [translator]} state]
  (with-result
    state
    (fn [{:keys [spec job transcript] :as ctx}]
      (let [{:keys [job-id source-language target-language]} spec]
        (r/let-ok [targets    (translate-segments translator transcript target-language
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

(defn- complete-render-job [job track]
  (r/let-ok [advanced (job/advance job)
             done     (job/advance advanced)]
            (r/ok (job/link-subtitle done (:id track)))))

(defn- render-subtitles [{:keys [renderer]} state]
  (with-result
    state
    (fn [{:keys [spec job transcript translated]}]
      (r/let-ok [track    (build-render-track spec translated)
                 rendered (p.sub/render-bytes renderer track)
                 job      (complete-render-job job track)]
                (r/ok {:job job
                       :transcript transcript
                       :translated translated
                       :subtitle-track track
                       :rendered rendered})))))

(def ^:private video-fsm
  (compile-stage-fsm
   [(->JobStage ::fsm/start start-translation)
    (->JobStage :vtranslate.pipeline/ingest ingest-media)
    (->JobStage :vtranslate.pipeline/transcribe transcribe-media)
    (->JobStage :vtranslate.pipeline/translate translate-transcript)
    (->JobStage :vtranslate.pipeline/render render-subtitles)]))

(defrecord TranslationPipeline [ports fsm]
  ITranslationPipeline
  (run-translation-job [_ spec]
    (:result (fsm/run fsm ports {:data spec}))))

(defn run-job
  [{:keys [media segmenter transcriber translator renderer]}
   {:keys [job-id source source-language target-language asset-kind format]
    :or   {asset-kind :media/video format :format/srt}}]
  (run-translation-job
   (->TranslationPipeline {:media media
                           :segmenter segmenter
                           :transcriber transcriber
                           :translator translator
                           :renderer renderer}
                          video-fsm)
   {:job-id job-id
    :source source
    :source-language source-language
    :target-language target-language
    :asset-kind asset-kind
    :format format}))

(defn- start-subtitle-translation [_ {:keys [job-id source target-language] :as spec}]
  (result-state
   (r/let-ok [asset (ing/make-media-asset
                     {:id (str job-id "-asset") :source-uri source :kind :media/subtitle})
              job   (job/make-translation-job
                     {:id job-id :asset-id (:id asset) :target-language target-language})]
             (r/ok {:spec spec :asset asset :job job}))))

(defn- read-subtitle-source [{:keys [reader]} state]
  (with-result
    state
    (fn [{:keys [spec] :as ctx}]
      (r/let-ok [text (p.src/read-text reader (:source spec))]
                (r/ok (assoc ctx :text text))))))

(defn- non-empty-cues [cues]
  (if (seq cues)
    (r/ok :non-empty)
    (r/err :error/render-failed {:reason "no cues parsed from source"})))

(defn- parse-subtitle-source [{:keys [parser]} state]
  (with-result
    state
    (fn [{:keys [spec text] :as ctx}]
      (let [{:keys [format reflow]} spec]
        (r/let-ok [parsed (p.sub/parse parser text format)
                   cues   (r/ok (let [cs (:cues parsed)]
                                  (if reflow (c.reflow/reflow cs reflow) cs)))
                   _      (non-empty-cues cues)]
                  (r/ok (assoc ctx :cues cues)))))))

(defn- translate-subtitle-cues [{:keys [translator]} state]
  (with-result
    state
    (fn [{:keys [spec cues] :as ctx}]
      (let [{:keys [source-language target-language]} spec]
        (r/let-ok [texts   (r/ok (c.so/cue-texts cues))
                   targets (p.tr/translate-batch translator texts
                                                 source-language target-language {})
                   tcues   (c.so/apply-translations cues targets)]
                  (r/ok (assoc ctx :translated-cues tcues)))))))

(defn- complete-subtitle-job [job track]
  (r/let-ok [ingesting (job/advance job)]
            (r/ok (job/link-subtitle (job/complete ingesting) (:id track)))))

(defn- render-subtitle-track [{:keys [renderer]} state]
  (with-result
    state
    (fn [{:keys [spec asset job translated-cues]}]
      (let [{:keys [job-id target-language format]} spec]
        (r/let-ok [track    (c.si/build-subtitle-track
                              translated-cues {:id (str job-id "-sub")
                                               :source-id (:id asset)
                                               :language target-language
                                               :format format})
                   job      (complete-subtitle-job job track)
                   rendered (p.sub/render-bytes renderer track)]
                  (r/ok {:job job :subtitle-track track :rendered rendered}))))))

(def ^:private subtitle-fsm
  (compile-stage-fsm
   [(->JobStage ::fsm/start start-subtitle-translation)
    (->JobStage :vtranslate.subtitle/read read-subtitle-source)
    (->JobStage :vtranslate.subtitle/parse parse-subtitle-source)
    (->JobStage :vtranslate.subtitle/translate translate-subtitle-cues)
    (->JobStage :vtranslate.subtitle/render render-subtitle-track)]))

(defn run-subtitle-job
  [{:keys [parser translator renderer] reader :source}
   {:keys [job-id source source-language target-language format reflow]
    :or   {format :format/srt}}]
  (run-translation-job
   (->TranslationPipeline {:reader reader
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