(ns vtranslate.engine.domain.rendering
  "Rendering bounded context — the SubtitleTrack aggregate root: the final,
   format-ready subtitle artifact. Owns ordered Cues (display-shaped lines over
   a time range) and a SubtitleFormat. DDD: references the TranslatedCues
   aggregate BY ID (:source-id). The adapter/Collect layer serializes a
   SubtitleTrack to bytes (srt/vtt/ass); this domain never touches IO."
  (:require [hive-dsl.adt :refer [defadt adt-case]]
            [hive-dsl.result :as r]
            [vtranslate.engine.shared :as shared]))

(defadt SubtitleFormat
  "Wire formats the renderer can emit."
  :format/srt
  :format/vtt
  :format/ass)

(def ^:private subtitle-formats
  #{:format/srt :format/vtt :format/ass})

(defadt TrackStatus
  "Lifecycle of a subtitle track. :track/failed is terminal."
  :track/draft
  :track/rendered
  :track/failed)

;; --- Cue (entity within the SubtitleTrack aggregate) -----------------------

(defrecord Cue [index range lines])

(defn make-cue
  "A display cue: 1-based index, a shared/TimeRange, and 1+ text lines.
   => (r/ok Cue) | (r/err :error/render-failed ...)."
  [{:keys [index start-ms end-ms lines]}]
  (r/let-ok [range (shared/make-time-range start-ms end-ms)]
    (if (and (seq lines) (every? string? lines))
      (r/ok (->Cue index range (vec lines)))
      (r/err :error/render-failed {:reason "cue has no text lines"}))))

;; --- Aggregate root --------------------------------------------------------

(defrecord SubtitleTrack [id source-id language format cues status])

(defn make-subtitle-track
  "Smart ctor: validates language + that the format is a known SubtitleFormat.
   => (r/ok SubtitleTrack) | (r/err ...)."
  [{:keys [id source-id language format]}]
  (r/let-ok [lang (shared/make-language language)]
    (if (contains? subtitle-formats format)
      (r/ok (map->SubtitleTrack
             {:id id
              :source-id source-id
              :language lang
              :format (subtitle-format format)
              :cues []
              :status (track-status :track/draft)}))
      (r/err :error/unsupported-format {:format format}))))

(defn add-cue
  "Append a validated Cue to the track."
  [track cue]
  (update track :cues conj cue))

(defn render
  "Seal the track once it has cues.
   => (r/ok track') | (r/err :error/render-failed ...)."
  [{:keys [cues] :as track}]
  (if (seq cues)
    (r/ok (assoc track :status (track-status :track/rendered)))
    (r/err :error/render-failed {:reason "no cues to render"})))

(defn extension
  "File extension for the track's format. adt-case ⇒ exhaustive."
  [{:keys [format]}]
  (adt-case SubtitleFormat format
    :format/srt "srt"
    :format/vtt "vtt"
    :format/ass "ass"))
