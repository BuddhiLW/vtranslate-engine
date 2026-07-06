(ns vtranslate.engine.domain.translation
  "Translation bounded context — turns a source Transcript into TranslatedCues
   for ONE target language. The TranslationUnit value object pairs source text
   with its translation over a time range; TranslatedCues is the aggregate root
   (one per target language). DDD: references the Transcript BY ID
   (:transcript-id). Deliberately NOT shared with rendering — rendering owns its
   own Cue/SubtitleTrack; this aggregate is the translation-stage output only."
  (:require [hive-dsl.adt :refer [defadt]]
            [hive-dsl.result :as r]
            [vtranslate.engine.shared :as shared]))

(defadt TranslationStatus
  "Lifecycle of a per-language translation. :translation/failed is terminal."
  :translation/pending
  :translation/translating
  :translation/complete
  :translation/failed)

;; --- TranslationUnit (value object) ----------------------------------------

(defrecord TranslationUnit [range source-language source-text target-text])

(defn make-translation-unit
  "Pair source text with its translation over a shared/TimeRange.
   => (r/ok TranslationUnit) | (r/err ...)."
  [{:keys [start-ms end-ms source-language source-text target-text]}]
  (r/let-ok [range (shared/make-time-range start-ms end-ms)
             lang  (if source-language (shared/make-language source-language) (r/ok nil))]
    (r/ok (->TranslationUnit range lang source-text target-text))))

;; --- Aggregate root --------------------------------------------------------

(defrecord TranslatedCues
  [id transcript-id source-language target-language units status])

(defn make-translated-cues
  "Smart ctor: validates BOTH languages and forbids a no-op (src = tgt).
   => (r/ok TranslatedCues) | (r/err ...)."
  [{:keys [id transcript-id source-language target-language]}]
  (r/let-ok [src (shared/make-language source-language)
             tgt (shared/make-language target-language)]
    (if (= src tgt)
      (r/err :error/translation-failed
             {:segment-id (str id)
              :reason "source and target language are identical"})
      (r/ok (map->TranslatedCues
             {:id id
              :transcript-id transcript-id
              :source-language src
              :target-language tgt
              :units []
              :status (translation-status :translation/pending)})))))

(defn begin
  "Move :translation/pending -> :translation/translating."
  [cues]
  (assoc cues :status (translation-status :translation/translating)))

(defn add-unit
  "Append a translated TranslationUnit."
  [cues unit]
  (update cues :units conj unit))

(defn complete
  "Seal the translation once at least one unit exists.
   => (r/ok cues') | (r/err :error/translation-failed ...)."
  [{:keys [units] :as cues}]
  (if (seq units)
    (r/ok (assoc cues :status (translation-status :translation/complete)))
    (r/err :error/translation-failed
           {:segment-id (str (:id cues))
            :reason "no units translated"})))

(defn unit-count
  [{:keys [units]}]
  (count units))
