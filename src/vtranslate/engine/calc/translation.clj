(ns vtranslate.engine.calc.translation
  "Promote (CPPB) — pure: zip a source Transcript with its translated strings
   into a TranslatedCues aggregate for one target language. No IO."
  (:require [clojure.string :as str]
            [hive-dsl.result :as r]
            [vtranslate.engine.shared :as shared]
            [vtranslate.engine.domain.translation :as tr]))

(defn build-translated-cues
  "Align `translations` onto transcript segments and fold a completed TranslatedCues.
   Segment :language is preserved on each TranslationUnit; transcript language
   remains the aggregate source-language.
   => (r/ok TranslatedCues) | (r/err :error/translation-failed ...)."
  [transcript translations {:keys [id target-language]}]
  (let [segments (:segments transcript)]
    (if (not= (count segments) (count translations))
      (r/err :error/translation-failed
             {:segment-id (str id)
              :reason (format "translation count %d != segment count %d"
                              (count translations) (count segments))})
      (r/let-ok [c0     (tr/make-translated-cues
                         {:id id
                          :transcript-id (:id transcript)
                          :source-language (:language transcript)
                          :target-language target-language})
                 filled (reduce
                         (fn [acc [seg target-text]]
                           (let [[s e] (shared/range-ms (:range seg))]
                             (r/let-ok [c acc
                                        u (tr/make-translation-unit
                                           (merge (shared/annotations seg)
                                                  {:start-ms s :end-ms e
                                                   :source-language (:language seg)
                                                   :source-text (:text seg)
                                                   :target-text target-text}))]
                               (r/ok (tr/add-unit c u)))))
                         (r/ok (tr/begin c0))
                         (map vector segments translations))]
        (tr/complete filled)))))

(defn normalize-targets
  "The target languages a job asks for, from either `:target-languages` (a
   collection) or `:target-language` (one). Blanks are dropped and order is
   preserved with duplicates removed, so the same language is never transcribed
   for twice. => vector of strings (possibly empty)."
  [{:keys [target-language target-languages]}]
  (into []
        (comp (map #(some-> % str str/trim))
              (remove str/blank?)
              (distinct))
        (if (seq target-languages)
          target-languages
          [target-language])))

(defn segment-source-language
  "Resolve one `segment`'s source language through the fallback chain: the segment's
   own :language, else the caller `fallback`, else the transcript language, else
   \"und\". Pure — the grouping key for language-routed translation batches."
  [transcript fallback segment]
  (or (:language segment) fallback (:language transcript) "und"))

(def verbatim-group
  "Grouping key for segments whose target text is their own text: segments marked
   :segment/verbatim? (e.g. an addon's decoder-placeholder marking), and speech
   already in the target language. That group is never sent to a translator:
   `verbatim-translations` carries it."
  ::verbatim)

(defn translation-group
  "The batch a `segment` translates in: `verbatim-group` for a segment marked
   :segment/verbatim? or :segment/omit? (text no viewer sees is not worth a
   translator call) or for speech whose source language is `target-language`,
   else its source language (`segment-source-language`). Segments in different
   source languages land in different batches, each translated from its own
   language."
  [transcript fallback target-language segment]
  (let [source (segment-source-language transcript fallback segment)]
    (if (or (:segment/verbatim? segment) (:segment/omit? segment)
            (= source target-language))
      verbatim-group
      source)))

(defn verbatim-translations
  "[[index text] ...] for an indexed `verbatim-group`: each segment's own text,
   unchanged. => (r/ok [[index text] ...])."
  [indexed-segments]
  (r/ok (mapv (fn [[i seg]] [i (:text seg)]) indexed-segments)))

(defn translation-count-error
  "The canonical :error/translation-failed Result for a batch whose produced count
   `actual` != the expected count `expected`; `id` tags the offending batch.
   => (r/err :error/translation-failed {:segment-id (str id) :reason ...})."
  [id expected actual]
  (r/err :error/translation-failed
         {:segment-id (str id)
          :reason (format "translation count %d != segment count %d" actual expected)}))

(defn target-outcomes
  "Sort per-target translation results into the targets that finished and the
   ones that did not, both in `targets` order. `results` holds one Result per
   target, or `unfinished` for a target the pool stopped waiting on.
   => {:delivered [{:target-language :translated}]
       :failed    [{:target-language :error ...}]}"
  [targets results unfinished]
  (reduce (fn [acc [lang res]]
            (cond
              (= unfinished res)
              (update acc :failed conj {:target-language lang
                                        :error :error/translation-failed
                                        :reason "translation timed out or threw"})

              (r/err? res)
              (update acc :failed conj (assoc res :target-language lang))

              :else
              (update acc :delivered conj {:target-language lang
                                           :translated (:ok res)})))
          {:delivered [] :failed []}
          (map vector targets results)))