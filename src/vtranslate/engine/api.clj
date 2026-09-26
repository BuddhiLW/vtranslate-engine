(ns vtranslate.engine.api
  (:require [hive-dsl.result :as r]
            [hive-weave.parallel :as wp]
            [vtranslate.engine.domain.job :as job]
            [vtranslate.engine.domain.ingestion :as ing]
            [vtranslate.engine.calc.transcription :as c.tx]
            [vtranslate.engine.calc.translation :as c.tr]
            [vtranslate.engine.calc.rendering :as c.rd]
            [vtranslate.engine.calc.paths :as c.paths]
            [vtranslate.engine.version :as engine-version]
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
            [vtranslate.engine.adapters.translator.augment :as augment]
            [vtranslate.engine.calc.progress :as c.progress]
            [vtranslate.engine.domain.transcription :as tx]
            [vtranslate.engine.providers.decorators :as decorators]
            [vtranslate.engine.calc.reflow.optimal]))

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

(defn- notify-progress! [resources event]
  (when-let [on-progress (:on-progress resources)]
    (try
      (on-progress event)
      (catch Throwable _ nil))))

(defn- stage-progress! [resources stage percent]
  (notify-progress! resources {:type :pipeline-progress
                               :stage stage
                               :percent percent}))

(defn- work-progress!
  "Report that `done` of `total` units of `stage`'s work have finished, as a
   percent inside the stage's band, with the count itself as `:detail`."
  [resources stage done total]
  (notify-progress! resources {:type :pipeline-progress
                               :stage stage
                               :percent (c.progress/within-band stage done total)
                               :detail {:done done :total total}}))

(defn- record-provider-attempt! [resources job-id attempt]
  (let [record (assoc attempt
                      :job-id job-id
                      :attempt (swap! (:provider-attempt-counter resources) inc))]
    (swap! (:provider-attempts resources) conj record)
    (notify-progress! resources {:type :provider-attempt
                                 :attempt record})))

(defn- provider-usage [attempts]
  (reduce
   (fn [usage attempt]
     (reduce (fn [acc key]
               (update acc key + (long (or (get-in attempt [:usage key]) 0))))
             usage
             [:input-tokens :output-tokens :total-tokens :cost-micros]))
   {:input-tokens 0 :output-tokens 0 :total-tokens 0 :cost-micros 0}
   attempts))

(defn- attach-provider-telemetry [result attempts]
  (let [records (vec (sort-by :attempt @attempts))
        usage (provider-usage records)
        telemetry {:provider-attempts records
                   :provider-cost-micros (:cost-micros usage)
                   :usage usage}]
    (if (r/ok? result)
      (update result :ok merge telemetry)
      (merge result telemetry))))

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
    :model       (or (get-in config [:transcriber-opts :model-path])
                     (get-in config [:transcriber-opts :model]))
    :language    source-language
    :segmenter   (get-in config [:providers :segmenter] (:segmenter config))
    :span-pad-ms (get-in config [:transcriber-opts :span-pad-ms] 200)
    :decorators  (pr-str (decorators/identities :transcriber config))
    :transcriber-knobs (pr-str (into (sorted-map) (select-keys (:transcriber-opts config) [:slice-spans? :temperature :prompt])))
    :engine-version engine-version/engine-version}))

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

(defn- cacheable-transcript?
  "True when `transcript` carries speech and may be stored in, or reused from,
   the transcript cache. A silent or otherwise segment-less transcript is
   neither stored nor trusted as a hit."
  [transcript]
  (boolean (seq (:segments transcript))))

(defn- transcribe-media
  "ASR, or the cached transcript of an identical earlier run. ASR is the only
   stage that costs minutes, so its result is persisted: a later failure —
   an expired key, a bad mux — never makes it run twice.

   Only a transcript that carries speech is stored or reused (see
   `cacheable-transcript?`); an empty one always sends the job back to ASR."
  [{:keys [transcript-cache] :as resources} state]
  (stage-progress! resources :transcribing 25)
  (pf/with-result
    state
    (fn [{:keys [spec asset job probe audio] :as ctx}]
      (let [cache (or transcript-cache p.cache/disabled)
            key   (transcript-cache-key spec probe (:config resources))]
        (r/let-ok [found  (p.cache/fetch cache key)
                   cached (r/ok (when (cacheable-transcript? found) found))
                   transcript (if cached
                                (r/ok cached)
                                (r/let-ok [fresh (run-asr resources spec asset probe audio)
                                           _     (if (cacheable-transcript? fresh)
                                                   (p.cache/store! cache key fresh)
                                                   (r/ok nil))]
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
          (ext/phase-middleware phase resources)))

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

(defn- translate-indexed-group
  [translator target-language on-provider-attempt model
   [source-language indexed-segments]]
  (if (= c.tr/verbatim-group source-language)
    (c.tr/verbatim-translations indexed-segments)
    (let [{:keys [indices values]} (batch/group-payload indexed-segments :text)]
      (r/let-ok [targets (p.tr/translate-batch translator values source-language target-language
                                               {:segment-indices indices
                                                :on-provider-attempt on-provider-attempt
                                                :model model})]
        (batch/zip-indices indices targets
                           (fn [expected actual]
                             (c.tr/translation-count-error source-language expected actual)))))))

(defn- collect-translations [translator target-language groups on-provider-attempt model]
  (reduce (fn [acc group]
            (r/let-ok [pairs acc
                       group-pairs (translate-indexed-group translator target-language
                                                           on-provider-attempt model group)]
              (r/ok (into pairs group-pairs))))
          (r/ok [])
          groups))

(defn- translate-segments
  [translator transcript target-language fallback-source-language
   on-provider-attempt model]
  (let [segments (:segments transcript)
        groups   (batch/index-groups
                  segments
                  #(c.tr/translation-group transcript fallback-source-language
                                           target-language %))]
    (r/let-ok [pairs (collect-translations translator target-language groups
                                           on-provider-attempt model)]
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
  [tr {:keys [job-id source-language model]} transcript target-language multi?
   on-provider-attempt]
  (r/let-ok [targets (translate-segments tr transcript target-language
                                         (explicit-source-language source-language)
                                         on-provider-attempt model)]
    (c.tr/build-translated-cues transcript targets
                                {:id (str job-id "-tc" (lang-suffix multi? target-language))
                                 :target-language target-language})))

(def ^:private max-target-concurrency
  "Most target languages translated at once. Each is one long provider request
   per batch, so the bound is about not hammering the provider, not about CPU."
  7)

(defn- target-concurrency
  "How many of `targets` translate at once: the configured bound, else every
   target up to `max-target-concurrency`. => positive long"
  [config targets]
  (or (get-in config [:translator-opts :target-concurrency])
      (max 1 (min max-target-concurrency (count targets)))))

(defn- translate-targets
  "Translate the one shared transcript into every target. Independent per
   language, so they run under a bounded pool; a timeout or throw surfaces as
   that target's failure rather than a nil. Order follows `targets`, not
   completion. `on-target-done` is called once per target as it finishes, in
   completion order, so progress can be reported while the others are still
   running. A failed target fails the job, unless
   `[:translator-opts :deliver-partial?]` is set and another target finished:
   then the finished ones are delivered and the rest travel as `:failed-targets`.
   => Result<{:outputs [{:target-language :translated}] :failed-targets [...]}>."
  [tr spec transcript targets config on-provider-attempt on-target-done]
  (let [multi?  (< 1 (count targets))
        results (wp/bounded-pmap
                 {:concurrency (target-concurrency config targets)
                  :timeout-ms  (or (get-in config [:translator-opts :target-timeout-ms])
                                   1800000)
                  :fallback    ::timeout}
                 (fn [lang]
                   (let [translated (translate-one-target tr spec transcript lang multi?
                                                          on-provider-attempt)]
                     (on-target-done)
                     translated))
                 targets)
        {:keys [delivered failed]} (c.tr/target-outcomes targets results ::timeout)]
    (cond
      (empty? failed)
      (r/ok {:outputs delivered :failed-targets []})

      (and (get-in config [:translator-opts :deliver-partial?]) (seq delivered))
      (r/ok {:outputs delivered :failed-targets failed})

      :else
      (let [{:keys [error] :as first-failure} (first failed)]
        (r/err error (-> first-failure
                         (dissoc :error)
                         (assoc :failed-targets failed
                                :delivered-targets (mapv :target-language delivered))))))))

(defn- translate-transcript
  "Fan out over every requested target language. The transcript is produced ONCE
   upstream and reused, because ASR dominates the cost of a job and translation
   does not. `:translated` stays bound to the first target so a single-target
   job's result shape is unchanged.

   Every batch the translator returns is reported as a `:translation-chunk`
   (its sources, translations and positions), so a caller can show the job's
   phrases as they are translated. See adapters.translator.observed.

   A SILENT transcript (media with no speech) translates to nothing and says so:
   there is no batch to send, so the stage advances the job with no outputs and
   the render stage produces an empty track rather than the job failing."
  [{:keys [translator config] :as resources} state]
  (stage-progress! resources :translating 60)
  (pf/with-result
    state
    (fn [{:keys [spec job transcript] :as ctx}]
      ;; :translate/decorate is an opaque (fn [translator] translator') a
      ;; pre-translate middleware may leave in ctx; opts augment outermost.
      (let [decorate (or (:translate/decorate ctx) identity)
            tr      (augment/wrap-opts
                     (decorate translator)
                     (assoc (:translate/opts ctx)
                            :on-chunk-translated
                            #(notify-progress! resources
                                               (assoc % :type :translation-chunk
                                                        :job-id (:job-id spec)))))
            targets (c.tr/normalize-targets spec)
            done    (atom 0)]
        (cond
          (empty? targets)
          (r/err :error/no-target-language
                 {:reason "job spec named no target language"})

          (tx/silent? transcript)
          (r/let-ok [job (job/advance job)]
            (r/ok (assoc ctx :job job :outputs [] :silent? true)))

          :else
          (r/let-ok [translated (translate-targets
                                 tr spec transcript targets config
                                 #(record-provider-attempt! resources (:job-id spec) %)
                                 ;; Targets finish concurrently; the lock keeps
                                 ;; each report's count in the order it is sent.
                                 #(locking done
                                    (work-progress! resources :translating
                                                    (swap! done inc) (count targets))))
                     job        (job/advance job)]
            (let [{:keys [outputs failed-targets]} translated]
              (r/ok (cond-> (assoc ctx
                                   :job job
                                   :outputs outputs
                                   :translated (:translated (first outputs)))
                      (seq failed-targets) (assoc :failed-targets failed-targets))))))))))

(def ^:private reserved-result-keys
  "Job-result keys owned by the render stage; middleware :result/extra must not
   overwrite them."
  [:spec :job :transcript :transcript-cached?
   :translated :subtitle-track :rendered :outputs :failed-targets])

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
  "Build and render the subtitle track for one translated target, its cues
   shaped by the spec's `:reflow` rules when it has any.
   => Result<output> with :subtitle-track and :rendered added."
  [renderer {:keys [job-id format reflow]} multi? {:keys [target-language translated] :as output}]
  (r/let-ok [track    (c.rd/build-subtitle-track
                       translated
                       {:id (str job-id "-sub" (lang-suffix multi? target-language))
                        :format format
                        :reflow reflow})
             rendered (p.sub/render-bytes renderer track)]
    (r/ok (assoc output :subtitle-track track :rendered rendered))))

(defn- render-subtitles
  "Render every target's cues. The first target also populates the flat
   :subtitle-track / :rendered result keys, so a single-target caller sees
   exactly what it always did.

   A silent job has no targets to render: it carries :silent? and an EMPTY
   :rendered, so a caller writing a sidecar file writes an empty subtitle rather
   than nothing at all, and can tell 'no speech' from 'not run'."
  [{:keys [renderer] :as resources} state]
  (stage-progress! resources :rendering 85)
  (pf/with-result
    state
    (fn [{:keys [spec job transcript transcript-cached? outputs failed-targets silent?] :as ctx}]
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
                           (cond-> {:spec spec
                                    :job job
                                    :transcript transcript
                                    :transcript-cached? (boolean transcript-cached?)
                                    :outputs rendered-outputs
                                    :translated (:translated (first rendered-outputs))
                                    :subtitle-track (:subtitle-track (first rendered-outputs))
                                    :rendered (:rendered (first rendered-outputs))}
                             silent?              (assoc :silent? true :rendered "")
                             (seq failed-targets) (assoc :failed-targets failed-targets))
                           (:result/extra ctx))]
        (r/ok result)))))

(def composer-default-suffix
  "The sink every composer falls back to when the caller names no `:output-uri`
   — `<source-without-ext>.subbed.mp4`, beside the source. Duplicated from the
   composer adapters ON PURPOSE: the multi-language sink below has to be able to
   name that same default BEFORE the composer sees it, because the composer
   cannot know a second language is coming."
  ".subbed.mp4")

(defn- compose-sink
  "Where one target's video is written.

   A single-target job keeps passing `output` straight through — nil included,
   which leaves the default to the composer exactly as before.

   A MULTI-target job must never pass nil: every composer defaults nil to the
   same `<source>.subbed.mp4`, so all eleven languages composed to one path and
   each burn silently overwrote the last — the job then served that one file
   (the language that happened to finish last) under every language's name. So
   the default is made explicit here and then tagged with the language, which is
   what `output` was already getting."
  [source output multi? target-language]
  (if-not multi?
    output
    (c.paths/language-variant
     (or output (c.paths/sibling-output source composer-default-suffix))
     target-language)))

(defn- compose-one
  "Mux one target's track into its own video. With more than one target the
   output path is tagged with the language, because burning subtitles is
   per-language by nature and two targets would otherwise write the same file.

   The job's caption style, output quality and watermark ride along, so how a
   video looks is a property of the request rather than of the deployment.

   `:watermark?` is assoc'd AFTER the caption style on purpose. The style comes
   off the wire and a caller can put anything in it; whether the video carries
   the mark is the SERVER's answer, and it has to be the one that survives."
  [muxer {:keys [source output caption quality watermark?]} multi?
   {:keys [target-language subtitle-track] :as out}]
  (r/let-ok [composed (p.comp/compose
                       muxer source subtitle-track
                       (cond-> (or caption {})
                         quality (assoc :quality quality)
                         true    (assoc :output-uri
                                        (compose-sink source output multi?
                                                      target-language))
                         true    (assoc :watermark? (boolean watermark?))))]
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
   first composed target so a single-target caller sees what it always did.

   Burning re-encodes the whole video once per language, which makes this the
   longest stage of a burn job by far, so each finished video is reported."
  [{:keys [muxer] :as resources} state]
  (stage-progress! resources :composing 95)
  (pf/with-result
    state
    (fn [{:keys [spec outputs] :as ctx}]
      (if-not muxer
        (r/ok ctx)
        (let [wanted (:mux-languages spec)
              multi? (< 1 (count outputs))
              total  (count (filter #(muxed-language? wanted (:target-language %))
                                    outputs))
              finished (atom 0)]
          (r/let-ok [composed (reduce
                               (fn [acc out]
                                 (r/let-ok [done acc]
                                   (if (muxed-language? wanted (:target-language out))
                                     (r/let-ok [one (compose-one muxer spec multi? out)]
                                       (do (work-progress! resources :composing
                                                           (swap! finished inc)
                                                           total)
                                           (r/ok (conj done one))))
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
  "Ingress A: demux + ASR + translate + render (+ optional mux).
   A job may name one `:target-language` or several `:target-languages`; the
   source is transcribed ONCE either way and each target translates that same
   transcript. `:caption` carries burn-in style, `:quality` the output preset,
   and `:watermark?` whether the burned video carries the VTranslate mark; all
   three are ignored when no muxer is configured.
   => Result<job-result> carrying :outputs, one entry per language."
  [{:keys [media segmenter transcriber translator renderer muxer config
           transcript-cache on-progress]}
   {:keys [job-id source source-language target-language target-languages
           mux-languages asset-kind format output caption quality watermark? reflow]
    :or   {asset-kind :media/video format :format/srt}}]
  (let [targets (c.tr/normalize-targets {:target-language target-language
                                         :target-languages target-languages})
        attempts (atom [])
        resources {:media media
                   :segmenter segmenter
                   :transcriber transcriber
                   :translator translator
                   :renderer renderer
                   :muxer muxer
                   :transcript-cache transcript-cache
                   :config config
                   :on-progress on-progress
                   :provider-attempts attempts
                   :provider-attempt-counter (atom 0)}]
    (stage-progress! resources :ingesting 10)
    (let [result (pf/run-pipeline
                  (pf/pipeline resources video-fsm)
                  {:job-id job-id
                   :source source
                   :source-language source-language
                   :target-language (or target-language (first targets))
                   :target-languages targets
                   :mux-languages mux-languages
                   :asset-kind asset-kind
                   :format format
                   :model (get-in config [:translator-opts :model])
                   :caption caption
                   :quality quality
                   :watermark? watermark?
                   ;; the job's own rules, else the deployment's
                   :reflow (or reflow (:reflow config))
                   :output output})]
      (when (r/ok? result)
        (stage-progress! resources :completed 100))
      (attach-provider-telemetry result attempts))))

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
    (fn [{:keys [spec job transcript transcript-cached?] :as ctx}]
      (r/let-ok [job    (job/complete job)
                 result (merge-result-extra {:spec spec
                                             :job job
                                             :transcript transcript
                                             :transcript-cached?
                                             (boolean transcript-cached?)}
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
  [{:keys [media segmenter transcriber transcript-cache config]}
   {:keys [job-id source source-language asset-kind]
    :or   {asset-kind :media/video}}]
  (pf/run-pipeline
   (pf/pipeline {:media media
                 :segmenter segmenter
                 :transcriber transcriber
                 :transcript-cache transcript-cache
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

(defn- reflow-cues
  "`cues` shaped by the spec's `:reflow` rules, untouched when there are none."
  [cues reflow]
  (if reflow (c.reflow/try-reflow cues reflow) (r/ok cues)))

(defn- parse-subtitle-source [{:keys [parser]} state]
  (pf/with-result
    state
    (fn [{:keys [spec text] :as ctx}]
      (let [{:keys [format reflow]} spec]
        (r/let-ok [parsed (p.sub/parse parser text format)
                   cues   (reflow-cues (:cues parsed) reflow)
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

;; ---------------------------------------------------------------------------
;; Compose pipeline (burn-only ingress)
;; ---------------------------------------------------------------------------

(defn- start-compose [_ {:keys [asset-kind] :as spec}]
  (start-job spec (or asset-kind :media/video) (constantly (r/ok nil))))

(defn- read-compose-subtitle
  "Read the sidecar subtitle named by `:subtitle`. The job's `:source` stays the
   VIDEO, which is what the muxer burns into."
  [{:keys [reader]} state]
  (pf/with-result
    state
    (fn [{:keys [spec] :as ctx}]
      (r/let-ok [text (p.src/read-text reader (:subtitle spec))]
        (r/ok (assoc ctx :text text))))))

(defn- parse-compose-subtitle [{:keys [parser]} state]
  (pf/with-result
    state
    (fn [{:keys [spec text] :as ctx}]
      (let [{:keys [format reflow]} spec]
        (r/let-ok [parsed (p.sub/parse parser text format)
                   cues   (r/ok (let [cs (:cues parsed)]
                                  (if reflow (c.reflow/reflow cs reflow) cs)))
                   _      (non-empty-cues cues)]
          (r/ok (assoc ctx :cues cues)))))))

(defn- compose-track
  "The parsed cues as the single output `compose-video` will burn."
  [_ state]
  (pf/with-result
    state
    (fn [{:keys [spec asset cues] :as ctx}]
      (let [{:keys [job-id target-language format]} spec]
        (r/let-ok [track (c.si/build-subtitle-track
                          cues {:id (str job-id "-sub")
                                :source-id (:id asset)
                                :language target-language
                                :format format})]
          (r/ok (assoc ctx
                       :subtitle-track track
                       :outputs [{:target-language target-language
                                  :subtitle-track track}])))))))

(defn- finalize-compose [_ state]
  (pf/with-result
    state
    (fn [{:keys [spec job subtitle-track outputs output-video] :as ctx}]
      (r/let-ok [job    (finalize-job job subtitle-track job/complete)
                 result (merge-result-extra {:spec spec
                                             :job job
                                             :outputs outputs
                                             :output-video output-video}
                                            (:result/extra ctx))]
        (r/ok result)))))

(def ^:private compose-fsm
  (pf/compile-stages
   [(pf/stage pf/start-id start-compose)
    (pf/stage :vtranslate.compose/read read-compose-subtitle)
    (pf/stage :vtranslate.compose/parse parse-compose-subtitle)
    (pf/stage :vtranslate.compose/track compose-track)
    (pf/stage :vtranslate.pipeline/compose compose-video)
    (pf/stage :vtranslate.compose/finalize finalize-compose)]))

(defn run-compose-job
  "Ingress D, burn-only: mux an ALREADY rendered subtitle into an ALREADY
   demuxed video. No ingest, no ASR, no translation and no rendering, so it is
   constructible without a transcriber and without a translator, and it reads no
   plaintext beyond the subtitle it is handed.

   `:source` is the video, `:subtitle` the rendered subtitle file and `:format`
   how to parse it; `:caption`, `:quality`, `:watermark?` and `:output` mean
   what they mean in `run-job`. One target only: burning is per-language by
   nature, and a caller wanting several calls this once per language.
   => (r/ok {:spec spec :job job :outputs [output] :output-video uri})
    | (r/err TranslationError)."
  [{:keys [muxer parser config on-progress] reader :source}
   {:keys [job-id source subtitle target-language format reflow asset-kind
           caption quality watermark? output]
    :or   {format :format/srt asset-kind :media/video}}]
  (let [resources {:reader reader
                   :parser parser
                   :muxer muxer
                   :config config
                   :on-progress on-progress
                   :provider-attempts (atom [])
                   :provider-attempt-counter (atom 0)}]
    (pf/run-pipeline
     (pf/pipeline resources compose-fsm)
     {:job-id job-id
      :source source
      :subtitle subtitle
      :target-language target-language
      :asset-kind asset-kind
      :format format
      :reflow reflow
      :caption caption
      :quality quality
      :watermark? watermark?
      :output output})))
