(ns vtranslate.engine.domain.job
  "Job-orchestration bounded context — the TranslationJob aggregate root, its
   lifecycle (JobState), and the closed set of domain failures (TranslationError).
   DDD: cross-aggregate references are BY ID — the job holds asset-id /
   transcript-id / subtitle-id, never embeds the MediaAsset, Transcript, or
   SubtitleTrack aggregates. ADTs use hive-dsl/defadt (do not hand-roll sum types)."
  (:require [hive-dsl.adt :refer [defadt adt-case]]
            [hive-dsl.result :as r]
            [vtranslate.engine.shared :as shared]))

;; --- Lifecycle (closed sum; forward-only, :job/failed terminal) ------------

(defadt JobState
  "Phase of a TranslationJob. Advances forward only; :job/failed is terminal."
  :job/pending
  :job/ingesting
  :job/transcribing
  :job/translating
  :job/rendering
  :job/completed
  :job/failed)

(def ^:private forward-path
  "Happy-path order of JobState variant keywords (excludes :job/failed)."
  [:job/pending :job/ingesting :job/transcribing
   :job/translating :job/rendering :job/completed])

;; --- Closed domain failures (the railway err categories) -------------------

(defadt TranslationError
  "Closed set of domain failure modes — every category `api/run-job` surfaces on
   its err channel. The variant keyword doubles as the `r/err` category; the
   schema map documents the data each variant carries."
  [:error/unsupported-format   {:format any?}]
  [:error/invalid-source       {:source any?}]
  [:error/source-unreadable    {:source-uri any?}]
  [:error/probe-failed         {:reason string?}]
  [:error/audio-extract-failed {:reason string?}]
  [:error/no-audio-stream      {:source-id string?}]
  [:error/segmentation-failed  {:reason string?}]
  [:error/asr-failed           {:reason string?}]
  [:error/unsupported-language {:language string?}]
  [:error/translation-failed   {:segment-id string? :reason string?}]
  [:error/render-failed        {:reason string?}]
  [:error/illegal-transition   {:from any?}]
  [:error/uncaught             {:phase any?}])

;; --- Aggregate root --------------------------------------------------------

(defrecord TranslationJob
  [id asset-id target-language state transcript-id subtitle-id error])

(defn make-translation-job
  "Smart constructor for a TranslationJob in :job/pending. References the ingested
   MediaAsset BY ID (asset-id); validates target-language against the shared
   registry.
   => (r/ok TranslationJob) | (r/err :error/unsupported-language ...)."
  [{:keys [id asset-id target-language]}]
  (r/let-ok [_ (shared/make-language target-language)]
    (r/ok (map->TranslationJob
           {:id id
            :asset-id asset-id
            :target-language target-language
            :state (job-state :job/pending)
            :transcript-id nil
            :subtitle-id nil
            :error nil}))))

(defn fail
  "Move a job to terminal :job/failed, carrying a TranslationError variant kw
   (the same category used on the railway err channel)."
  [job error-kw]
  (assoc job :state (job-state :job/failed) :error error-kw))

(defn advance
  "Advance to the next happy-path phase.
   => (r/ok job') | (r/err :error/illegal-transition {:from variant})."
  [{:keys [state] :as job}]
  (let [variant (:adt/variant state)
        idx     (.indexOf forward-path variant)]
    (if (and (nat-int? idx) (< (inc idx) (count forward-path)))
      (r/ok (assoc job :state (job-state (nth forward-path (inc idx)))))
      (r/err :error/illegal-transition {:from variant}))))

(defn link-transcript
  "Record the produced Transcript on the job BY ID (DDD: reference, never embed)."
  [job transcript-id]
  (assoc job :transcript-id transcript-id))

(defn link-subtitle
  "Record the produced SubtitleTrack on the job BY ID (DDD: reference, never embed)."
  [job subtitle-id]
  (assoc job :subtitle-id subtitle-id))

(defn describe
  "One-line human label for the job's phase. adt-case ⇒ adding a JobState
   variant breaks this at compile time (exhaustiveness checking)."
  [{:keys [state]}]
  (adt-case JobState state
    :job/pending      "pending"
    :job/ingesting    "extracting audio"
    :job/transcribing "transcribing"
    :job/translating  "translating"
    :job/rendering    "rendering subtitles"
    :job/completed    "completed"
    :job/failed       "failed"))
