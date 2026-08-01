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
                                           {:start-ms s :end-ms e
                                            :source-language (:language seg)
                                            :source-text (:text seg)
                                            :target-text target-text})]
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

(defn translation-count-error
  "The canonical :error/translation-failed Result for a batch whose produced count
   `actual` != the expected count `expected`; `id` tags the offending batch.
   => (r/err :error/translation-failed {:segment-id (str id) :reason ...})."
  [id expected actual]
  (r/err :error/translation-failed
         {:segment-id (str id)
          :reason (format "translation count %d != segment count %d" actual expected)}))