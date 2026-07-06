(ns vtranslate.engine.calc.translation
  "Promote (CPPB) — pure: zip a source Transcript with its translated strings
   into a TranslatedCues aggregate for one target language. No IO."
  (:require [hive-dsl.result :as r]
            [vtranslate.engine.domain.translation :as tr]))

(defn- seg-range-ms [segment]
  [(get-in segment [:range :start :ms])
   (get-in segment [:range :end :ms])])

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
                           (let [[s e] (seg-range-ms seg)]
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
