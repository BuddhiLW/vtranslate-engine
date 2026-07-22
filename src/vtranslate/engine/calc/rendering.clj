(ns vtranslate.engine.calc.rendering
  "Promote (CPPB) — pure: turn a TranslatedCues aggregate into a render-ready
   SubtitleTrack. Term shift TranslationUnit -> Cue at the rendering boundary. No IO."
  (:require [hive-dsl.result :as r]
            [vtranslate.engine.shared :as shared]
            [vtranslate.engine.calc.promote :as promote]
            [vtranslate.engine.domain.rendering :as rd]))

(defn- unit->cue
  "Promote one TranslationUnit into a render Cue at the given 1-based index.
   => (r/ok Cue) | (r/err :error/render-failed ...)."
  [index unit]
  (let [[start-ms end-ms] (shared/range-ms (:range unit))]
    (rd/make-cue {:index index :start-ms start-ms :end-ms end-ms
                  :lines [(:target-text unit)]})))

(defn build-subtitle-track
  "Turn a TranslatedCues aggregate into a render-ready SubtitleTrack: make the
   track, fill its cues (via the shared promote fold), seal it. `spec` =
   {:id :format}; language + source-id come from the TranslatedCues.
   => (r/ok SubtitleTrack) | (r/err :error/render-failed ...)."
  [translated-cues {:keys [id format]}]
  (r/let-ok [track  (rd/make-subtitle-track
                     {:id id
                      :source-id (:id translated-cues)
                      :language (:target-language translated-cues)
                      :format format})
             filled (promote/fill-cues track unit->cue (:units translated-cues))]
    (rd/render filled)))
