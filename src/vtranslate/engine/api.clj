(ns vtranslate.engine.api
  "Pipeline + Boundary (CPPB) — orchestrates one translation job end to end over
   INJECTED ports (DIP: depends on protocols, never on adapters). Pure promoters
   (calc.*) assemble the aggregates; effects happen only in the port calls. The
   whole flow is a hive-dsl railway — the first err short-circuits to the caller."
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
            [vtranslate.engine.port.source :as p.src]))

(defn- segment-audio
  "Cutting phase A. With a `segmenter` present, cut the audio source into spans
   (bounded by the probed :duration-ms) to steer the transcriber; without one,
   the transcriber falls back to ASR-native timestamps (=> (r/ok nil)).
   => (r/ok [{:start-ms n :end-ms n} ...] | nil) | (r/err ...)."
  [segmenter audio probe]
  (if segmenter
    (r/let-ok [out (p.seg/segment segmenter audio {:duration-ms (:duration-ms probe)})]
      (r/ok (:spans out)))
    (r/ok nil)))

(defn run-job
  "Run a job `spec` through the full pipeline using `ports` — a map
   {:media :transcriber :translator :renderer} of protocol impls, plus an
   OPTIONAL :segmenter (cutting phase A; nil => ASR-native timestamps).
   `spec` = {:job-id :source :source-language :target-language :asset-kind :format}.
   => (r/ok {:job :transcript :translated :subtitle-track :rendered})
      | (r/err <domain-error> ...)."
  [{:keys [media segmenter transcriber translator renderer]}
   {:keys [job-id source source-language target-language asset-kind format]
    :or   {asset-kind :media/video format :format/srt}}]
  (r/let-ok [asset      (ing/make-media-asset
                         {:id (str job-id "-asset") :source-uri source :kind asset-kind})
             job        (job/make-translation-job
                         {:id job-id :asset-id (:id asset) :target-language target-language})
             probe      (p.media/probe media source)
             ready      (ing/ready (ing/with-probe asset probe))
             job        (job/advance job)                       ; pending -> ingesting
             audio      (p.media/extract-audio media source {})
             spans      (segment-audio segmenter audio probe)   ; cutting phase A (optional)
             asr        (p.asr/transcribe transcriber audio source-language {:spans spans})
             transcript (c.tx/build-transcript
                         {:id (str job-id "-tx") :asset-id (:id ready)
                          :language source-language :segments (:segments asr)})
             job        (job/advance job)                       ; ingesting -> transcribing
             job        (r/ok (job/link-transcript job (:id transcript)))
             texts      (r/ok (mapv :text (:segments transcript)))
             targets    (p.tr/translate-batch translator texts source-language target-language {})
             translated (c.tr/build-translated-cues
                         transcript targets
                         {:id (str job-id "-tc") :target-language target-language})
             job        (job/advance job)                       ; transcribing -> translating
             track      (c.rd/build-subtitle-track
                         translated {:id (str job-id "-sub") :format format})
             job        (job/advance job)                       ; translating -> rendering
             rendered   (p.sub/render-bytes renderer track)
             job        (job/advance job)                       ; rendering -> completed
             job        (r/ok (job/link-subtitle job (:id track)))]
    (r/ok {:job job
           :transcript transcript
           :translated translated
           :subtitle-track track
           :rendered rendered})))

(defn run-subtitle-job
  "Ingress B — the no-ASR subtitle path over INJECTED ports (DIP). Read the
   subtitle source, parse it to boundary cue-maps, optionally reflow (cutting
   phase B), translate each cue, promote the translated cues into a target-
   language SubtitleTrack, and render it. `ports` = a map
   {:source :parser :translator :renderer} of protocol impls; `spec` =
   {:job-id :source :source-language :target-language :format :reflow}, where
   :format is the SubtitleFormat to emit (default :format/srt) and :reflow is an
   OPTIONAL reflow rule-map (absent => cues pass through 1:1, structure intact).
   Railway: the first err short-circuits to the caller.
   => (r/ok {:job :subtitle-track :rendered}) | (r/err <domain-error> ...)."
  [{:keys [parser translator renderer] reader :source}
   {:keys [job-id source source-language target-language format reflow]
    :or   {format :format/srt}}]
  (r/let-ok [asset      (ing/make-media-asset
                         {:id (str job-id "-asset") :source-uri source :kind :media/subtitle})
             job        (job/make-translation-job
                         {:id job-id :asset-id (:id asset) :target-language target-language})
             text       (p.src/read-text reader source)      ; boundary read
             parsed     (p.sub/parse parser text format)      ; wire text -> cue-maps
             cues       (r/ok (let [cs (:cues parsed)]        ; cutting phase B (optional)
                                (if reflow (c.reflow/reflow cs reflow) cs)))
             _          (if (seq cues)
                          (r/ok :non-empty)
                          (r/err :error/render-failed {:reason "no cues parsed from source"}))
             texts      (r/ok (c.so/cue-texts cues))
             targets    (p.tr/translate-batch translator texts
                                              source-language target-language {})
             tcues      (c.so/apply-translations cues targets)
             track      (c.si/build-subtitle-track
                         tcues {:id (str job-id "-sub") :source-id (:id asset)
                                :language target-language :format format})
             job        (job/advance job)                     ; pending -> ingesting
             job        (r/ok (job/link-subtitle (job/complete job) (:id track)))
             rendered   (p.sub/render-bytes renderer track)]
    (r/ok {:job job :subtitle-track track :rendered rendered})))
