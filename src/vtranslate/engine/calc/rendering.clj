(ns vtranslate.engine.calc.rendering
  "Promote (CPPB) — pure: turn a TranslatedCues aggregate into a render-ready
   SubtitleTrack. Term shift TranslationUnit -> Cue at the rendering boundary. No IO."
  (:require [hive-dsl.result :as r]
            [vtranslate.engine.shared :as shared]
            [vtranslate.engine.domain.rendering :as rd]))

(defn- unit->cue
  "Promote one TranslationUnit into a render Cue at the given 1-based index.
   => (r/ok Cue) | (r/err :error/render-failed ...)."
  [index unit]
  (let [[start-ms end-ms] (shared/range-ms (:range unit))]
    (rd/make-cue {:index index :start-ms start-ms :end-ms end-ms
                  :lines [(:target-text unit)]})))

(defn- add-unit
  "Reducing step over a Result-threaded track: append `unit` as the cue at
   `index`, short-circuiting on the first error."
  [track-result [index unit]]
  (r/let-ok [track track-result
             cue   (unit->cue index unit)]
    (r/ok (rd/add-cue track cue))))

(defn- fill-cues
  "Fold every TranslationUnit of `translated-cues` onto `track` as 1-based Cues.
   => (r/ok SubtitleTrack) | (r/err ...)."
  [track translated-cues]
  (->> (:units translated-cues)
       (map-indexed (fn [i unit] [(inc i) unit]))
       (reduce add-unit (r/ok track))))

(defn build-subtitle-track
  "Turn a TranslatedCues aggregate into a render-ready SubtitleTrack: make the
   track, fill its cues, seal it. `spec` = {:id :format}; language + source-id
   come from the TranslatedCues.
   => (r/ok SubtitleTrack) | (r/err :error/render-failed ...)."
  [translated-cues {:keys [id format]}]
  (r/let-ok [track  (rd/make-subtitle-track
                     {:id id
                      :source-id (:id translated-cues)
                      :language (:target-language translated-cues)
                      :format format})
             filled (fill-cues track translated-cues)]
    (rd/render filled)))
