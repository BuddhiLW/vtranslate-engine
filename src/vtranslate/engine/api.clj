(ns vtranslate.engine.api
  (:require [hive-dsl.result :as r]
            [hive-weave.parallel :as wp]
            [vtranslate.engine.domain.job :as job]
            [vtranslate.engine.domain.ingestion :as ing]
            [vtranslate.engine.calc.transcription :as c.tx]
            [vtranslate.engine.calc.translation :as c.tr]
            [vtranslate.engine.calc.rendering :as c.rd]
            [vtranslate.engine.calc.paths :as c.paths]
            [vtranslate.engine.calc.cache-key :as ck]
            [vtranslate.engine.port.transcript-cache :as p.cache]
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

(defn- transcript-cache-key
  "Identity of this transcription: the audio's CONTENT hash plus every setting
   that shapes the result. Hashing the file costs well under a second against
   the minutes ASR takes, and it means a renamed or moved video still hits while
   a same-sized replacement misses."
  [{:keys [source source-language]} _probe config]
  (ck/transcript-key
   {:content-sha (ck/file-sha source)
    :provider    (get-in config [:providers :transcriber] (:transcriber config))
    :model       (get-in config [:transcriber-opts :model-path])
    :language    source-language
    :segmenter   (get-in config [:providers :segmenter] (:segmenter config))
    :span-pad-ms (get-in config [:transcriber-opts :span-pad-ms])}))

(defn- run-asr
  "Segment, transcribe and build the Transcript. => Result<Transcript>."
  [{:keys [segmenter transcriber]} {:keys [job-id source-language]} asset probe audio]
  (r/let-ok [spans (segment-audio segmenter audio probe)
             asr   (p.asr/transcribe transcriber audio
                                     (explicit-source-language source-language)
                                     {:spans spans})]
    (c.tx/build-transcript {:id (str job-id "-tx") :asset-id (:id asset)
                            :language (transcript-language source-language)
                            :segments (:segments asr)})))

(defn- transcribe-media
  "ASR, or the cached transcript of an identical earlier run. ASR is the only
   stage that costs minutes, so its result is persisted: a later failure —
   an expired key, a bad mux — never makes it run twice."
  [{:keys [transcript-cache] :as resources} state]
  (pf/with-result
    state
    (fn [{:keys [spec asset job probe audio] :as ctx}]
      (let [cache (or transcript-cache p.cache/disabled)
            key   (transcript-cache-key spec probe (:config resources))]
        (r/let-ok [cached (p.cache/fetch cache key)
                   transcript (if cached
                                (r/ok cached)
                                (r/let-ok [fresh (run-asr resources spec asset probe audio)
                                           _     (p.cache/store! cache key fresh)]
                                  (r/ok fresh)))
                   job (job/advance job)]
          (r/ok (assoc ctx
                       :job (job/link-transcript job (:id transcript))
                       :transcript transcript
                       :transcript-cached? (boolean cached))))))))

(defn- fold-middleware
  "Fold every middleware registered for `phase` over ctx (r/let-ok
   short-circuit). => Result<ctx>."
  [phase resources ctx]
  (reduce (fn [acc mw] (r/let-ok [c acc] (mw resources c)))
          (r/ok ctx)
          (ext/middleware phase resources)))

(defn- apply-extensions
  "OCP extension point: fold every registered pre-translate middleware over the
   pipeline context. Middleware augment ctx (e.g. add :translate/opts for the
   translator or :result/extra for the job result); with no addon loaded there
   is no middleware and this is a no-op."
  [resources state]
  (pf/with-result
    state
    (fn [ctx] (fold-middleware :vtranslate.pipeline/pre-translate resources ctx))))

(defn- apply-post-extensions
  "OCP extension point: fold every registered post-translate middleware over the
   pipeline context. Middleware see :translated and may still augment
   :result/extra before render; with no addon loaded this is a no-op."
  [resources state]
  (pf/with-result
    state
    (fn [ctx] (fold-middleware :vtranslate.pipeline/post-translate resources ctx))))

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

(defn- lang-suffix
  "Id suffix distinguishing one target's artefacts from another's. Empty for a
   single-target job, whose ids must not change."
  [multi? target-language]
  (if multi? (str "-" target-language) ""))

(defn- translate-one-target
  "Translate the single shared transcript into `target-language`.
   => Result<TranslatedCues>."
  [tr {:keys [job-id source-language]} transcript target-language multi?]
  (r/let-ok [targets (translate-segments tr transcript target-language
                                         (explicit-source-language source-language))]
    (c.tr/build-translated-cues transcript targets
                                {:id (str job-id "-tc" (lang-suffix multi? target-language))
                                 :target-language target-language})))

(def ^:private default-target-concurrency
  "Target languages translated at once. Each is an independent chain of provider
   calls, so the bound is about not hammering the provider, not about CPU."
  3)

(defn- translate-targets
  "Translate the one shared transcript into every target. Independent per
   language, so they run under a bounded pool; a timeout or throw surfaces as
   that target's error rather than a nil, and the FIRST failure fails the job.
   Order follows `targets`, not completion. => Result<[{:target-language :translated}]>."
  [tr spec transcript targets config]
  (let [multi?  (< 1 (count targets))
        results (wp/bounded-pmap
                 {:concurrency (or (get-in config [:translator-opts :target-concurrency])
                                   default-target-concurrency)
                  :timeout-ms  (or (get-in config [:translator-opts :target-timeout-ms])
                                   1800000)
                  :fallback    ::timeout}
                 (fn [lang]
                   [lang (translate-one-target tr spec transcript lang multi?)])
                 targets)]
    (reduce (fn [acc [lang res]]
              (r/let-ok [done acc]
                (cond
                  (= ::timeout res)
                  (r/err :error/translation-failed
                         {:target-language lang :reason "translation timed out"})

                  (r/err? res) res

                  :else
                  (r/ok (conj done {:target-language lang :translated (:ok res)})))))
            (r/ok [])
            (map vector targets (map second results)))))

(defn- translate-transcript
  "Fan out over every requested target language. The transcript is produced ONCE
   upstream and reused, because ASR dominates the cost of a job and translation
   does not. `:translated` stays bound to the first target so a single-target
   job's result shape is unchanged."
  [{:keys [translator config]} state]
  (pf/with-result
    state
    (fn [{:keys [spec job transcript] :as ctx}]
      (let [tr      (augment/wrap-opts translator (:translate/opts ctx))
            targets (c.tr/normalize-targets spec)]
        (if (empty? targets)
          (r/err :error/no-target-language
                 {:reason "job spec named no target language"})
          (r/let-ok [outputs (translate-targets tr spec transcript targets config)
                     job     (job/advance job)]
            (r/ok (assoc ctx
                         :job job
                         :outputs outputs
                         :translated (:translated (first outputs))))))))))

(def ^:private reserved-result-keys
  "Job-result keys owned by the render stage; middleware :result/extra must not
   overwrite them."
  [:spec :job :transcript :translated :subtitle-track :rendered :outputs])

(defn- merge-result-extra
  "Merge middleware :result/extra into the job result. => Result<job-result>;
   a collision with a reserved result key fails loud onto
   :error/result-key-clobber rather than silently clobbering the pipeline output."
  [result extra]
  (let [clobbered (vec (filter #(contains? extra %) reserved-result-keys))]
    (if (seq clobbered)
      (r/err :error/result-key-clobber {:keys clobbered})
      (r/ok (merge result extra)))))

(defn- render-one-output
  "Build and render the subtitle track for one translated target.
   => Result<output> with :subtitle-track and :rendered added."
  [renderer {:keys [job-id format]} multi? {:keys [target-language translated] :as output}]
  (r/let-ok [track    (c.rd/build-subtitle-track
                       translated
                       {:id (str job-id "-sub" (lang-suffix multi? target-language))
                        :format format})
             rendered (p.sub/render-bytes renderer track)]
    (r/ok (assoc output :subtitle-track track :rendered rendered))))

(defn- render-subtitles
  "Render every target's cues. The first target also populates the flat
   :subtitle-track / :rendered result keys, so a single-target caller sees
   exactly what it always did."
  [{:keys [renderer]} state]
  (pf/with-result
    state
    (fn [{:keys [spec job transcript outputs] :as ctx}]
      (r/let-ok [rendered-outputs (reduce
                                   (fn [acc output]
                                     (r/let-ok [done acc
                                                one  (render-one-output
                                                      renderer spec
                                                      (< 1 (count outputs)) output)]
                                       (r/ok (conj done one))))
                                   (r/ok [])
                                   outputs)
                 job      (finalize-job job (:subtitle-track (first rendered-outputs))
                                        job/advance)
                 result   (merge-result-extra
                           {:spec spec
                            :job job
                            :transcript transcript
                            :outputs rendered-outputs
                            :translated (:translated (first rendered-outputs))
                            :subtitle-track (:subtitle-track (first rendered-outputs))
                            :rendered (:rendered (first rendered-outputs))}
                           (:result/extra ctx))]
        (r/ok result)))))

(defn- compose-one
  "Mux one target's track into its own video. With more than one target the
   output path is tagged with the language, because burning subtitles is
   per-language by nature and two targets would otherwise write the same file.

   The job's caption style and output quality ride along, so how a video looks
   is a property of the request rather than of the deployment."
  [muxer {:keys [source output caption quality]} multi?
   {:keys [target-language subtitle-track] :as out}]
  (r/let-ok [composed (p.comp/compose
                       muxer source subtitle-track
                       (cond-> (or caption {})
                         quality (assoc :quality quality)
                         true    (assoc :output-uri
                                        (if (and multi? output)
                                          (c.paths/language-variant
                                           output target-language)
                                          output))))]
    (r/ok (assoc out :output-video (or (:output-uris composed)
                                       (:output-uri composed))))))

(defn- muxed-language?
  "Whether `target-language` should get a video. `mux-languages` nil means every
   target; a collection restricts it, so a job can subtitle three languages and
   burn only one."
  [mux-languages target-language]
  (or (nil? mux-languages)
      (contains? (set (map str mux-languages)) (str target-language))))

(defn- compose-video
  "Mux the targets a muxer is configured for. :output-video stays bound to the
   first composed target so a single-target caller sees what it always did."
  [{:keys [muxer]} state]
  (pf/with-result
    state
    (fn [{:keys [spec outputs] :as ctx}]
      (if-not muxer
        (r/ok ctx)
        (let [wanted (:mux-languages spec)
              multi? (< 1 (count outputs))]
          (r/let-ok [composed (reduce
                               (fn [acc out]
                                 (r/let-ok [done acc]
                                   (if (muxed-language? wanted (:target-language out))
                                     (r/let-ok [one (compose-one muxer spec multi? out)]
                                       (r/ok (conj done one)))
                                     (r/ok (conj done out)))))
                               (r/ok [])
                               outputs)]
            (r/ok (assoc ctx
                         :outputs composed
                         :output-video (some :output-video composed)))))))))

(def ^:private video-fsm
  (pf/compile-stages
   [(pf/stage pf/start-id start-translation)
    (pf/stage :vtranslate.pipeline/ingest ingest-media)
    (pf/stage :vtranslate.pipeline/transcribe transcribe-media)
    (pf/stage :vtranslate.pipeline/extend apply-extensions)
    (pf/stage :vtranslate.pipeline/translate translate-transcript)
    (pf/stage :vtranslate.pipeline/extend-post apply-post-extensions)
    (pf/stage :vtranslate.pipeline/render render-subtitles)
    (pf/stage :vtranslate.pipeline/compose compose-video)]))

(defn run-job
  "Ingress A — demux + ASR + translate + render (+ optional mux).
   A job may name one `:target-language` or several `:target-languages`; the
   source is transcribed ONCE either way and each target translates that same
   transcript. `:caption` carries burn-in style and `:quality` the output
   preset; both are ignored when no muxer is configured.
   => Result<job-result> carrying :outputs, one entry per language."
  [{:keys [media segmenter transcriber translator renderer muxer config
           transcript-cache]}
   {:keys [job-id source source-language target-language target-languages
           mux-languages asset-kind format output caption quality]
    :or   {asset-kind :media/video format :format/srt}}]
  (let [targets (c.tr/normalize-targets {:target-language target-language
                                         :target-languages target-languages})]
    (pf/run-pipeline
     (pf/pipeline {:media media
                   :segmenter segmenter
                   :transcriber transcriber
                   :translator translator
                   :renderer renderer
                   :muxer muxer
                   :transcript-cache transcript-cache
                   :config config}
                  video-fsm)
     {:job-id job-id
      :source source
      :source-language source-language
      :target-language (or target-language (first targets))
      :target-languages targets
      :mux-languages mux-languages
      :asset-kind asset-kind
      :format format
      :caption caption
      :quality quality
      :output output})))

;; ---------------------------------------------------------------------------
;; Transcription pipeline (ASR-only ingress)
;; ---------------------------------------------------------------------------

(def transcription-target-language
  "Target language recorded on an ASR-only job: BCP-47 'und' (undetermined)."
  "und")

(defn- start-transcription [_ {:keys [asset-kind] :as spec}]
  (start-job spec (or asset-kind :media/video) (constantly (r/ok nil))))

(defn- finalize-transcription [_ state]
  (pf/with-result
    state
    (fn [{:keys [spec job transcript] :as ctx}]
      (r/let-ok [job    (job/complete job)
                 result (merge-result-extra {:spec spec
                                             :job job
                                             :transcript transcript}
                                            (:result/extra ctx))]
        (r/ok result)))))

(def ^:private transcription-fsm
  (pf/compile-stages
   [(pf/stage pf/start-id start-transcription)
    (pf/stage :vtranslate.pipeline/ingest ingest-media)
    (pf/stage :vtranslate.pipeline/transcribe transcribe-media)
    (pf/stage :vtranslate.pipeline/extend apply-extensions)
    (pf/stage :vtranslate.pipeline/finalize finalize-transcription)]))

(defn run-transcription-job
  "ASR-only ingress: ingest + transcribe. No translation, render, or compose.
   => (r/ok {:spec spec :job job :transcript transcript}) | (r/err TranslationError)."
  [{:keys [media segmenter transcriber config]}
   {:keys [job-id source source-language asset-kind]
    :or   {asset-kind :media/video}}]
  (pf/run-pipeline
   (pf/pipeline {:media media
                 :segmenter segmenter
                 :transcriber transcriber
                 :config config}
                transcription-fsm)
   {:job-id job-id
    :source source
    :source-language source-language
    :asset-kind asset-kind
    :target-language transcription-target-language}))

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
                                                 (explicit-source-language source-language)
                                                 target-language {})
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
