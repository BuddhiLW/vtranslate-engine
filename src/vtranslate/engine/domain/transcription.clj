(ns vtranslate.engine.domain.transcription
  "Transcription bounded context — the Transcript aggregate root produced by ASR.
   A Transcript owns its ordered Segments (entities inside the aggregate) and a
   per-segment Confidence value object. DDD: it references the MediaAsset BY ID
   (:asset-id) and never embeds it. Time ranges reuse the shared kernel."
  (:require [hive-dsl.adt :refer [defadt]]
            [hive-dsl.result :as r]
            [vtranslate.engine.shared :as shared]))

(defadt TranscriptStatus
  "Lifecycle of an ASR transcript. :transcript/failed is terminal."
  :transcript/empty
  :transcript/partial
  :transcript/complete
  :transcript/failed)

;; --- Confidence (value object, 0.0..1.0) -----------------------------------

(defrecord Confidence [score])

(defn make-confidence
  "Smart ctor: score must be a number in [0.0, 1.0]."
  [score]
  (if (and (number? score) (<= 0.0 (double score) 1.0))
    (r/ok (->Confidence (double score)))
    (r/err :error/invalid-confidence {:score score})))

;; --- Segment (entity within the Transcript aggregate) ----------------------

(defrecord Segment [index range text confidence language])

(defn make-segment
  "Build a Segment over a shared/TimeRange. Validates range, confidence, and
   optional source language.
   => (r/ok Segment) | (r/err ...)."
  [{:keys [index start-ms end-ms text confidence language]}]
  (r/let-ok [range (shared/make-time-range start-ms end-ms)
             conf  (make-confidence confidence)
             lang  (if language (shared/make-language language) (r/ok nil))]
    (r/ok (->Segment index range text conf lang))))

;; --- Aggregate root --------------------------------------------------------

(defrecord Transcript [id asset-id language segments status])

(defn make-transcript
  "Smart ctor for an empty Transcript bound to a MediaAsset id + language.
   => (r/ok Transcript) | (r/err :error/unsupported-language ...)."
  [{:keys [id asset-id language]}]
  (r/let-ok [lang (shared/make-language language)]
    (r/ok (map->Transcript
           {:id id
            :asset-id asset-id
            :language lang
            :segments []
            :status (transcript-status :transcript/empty)}))))

(defn add-segment
  "Append a validated Segment; moves status to :transcript/partial."
  [transcript segment]
  (-> transcript
      (update :segments conj segment)
      (assoc :status (transcript-status :transcript/partial))))

(defn complete
  "Seal the transcript. An empty transcript cannot complete.
   => (r/ok transcript') | (r/err :error/asr-failed ...)."
  [{:keys [segments] :as transcript}]
  (if (seq segments)
    (r/ok (assoc transcript :status (transcript-status :transcript/complete)))
    (r/err :error/asr-failed {:reason "no segments produced"})))

(defn total-duration-ms
  "Sum of segment durations — a calc over the shared kernel."
  [{:keys [segments]}]
  (reduce + 0 (map #(shared/duration-ms (:range %)) segments)))
