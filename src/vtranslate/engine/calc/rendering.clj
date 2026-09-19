(ns vtranslate.engine.calc.rendering
  "Promote (CPPB) — pure: turn a TranslatedCues aggregate into a render-ready
   SubtitleTrack. Term shift TranslationUnit -> Cue at the rendering boundary. No IO."
  (:require [hive-dsl.result :as r]
            [vtranslate.engine.shared :as shared]
            [vtranslate.engine.calc.promote :as promote]
            [vtranslate.engine.domain.rendering :as rd]
            [vtranslate.engine.calc.reflow :as reflow]
            [clojure.string :as str]
            [vtranslate.engine.calc.reflow.optimal]))

(defn- unit->cue
  "Promote one TranslationUnit into a render Cue at the given 1-based index.
   => (r/ok Cue) | (r/err :error/render-failed ...)."
  [index unit]
  (let [[start-ms end-ms] (shared/range-ms (:range unit))]
    (rd/make-cue {:index index :start-ms start-ms :end-ms end-ms
                  :lines [(:target-text unit)]})))

(defn- unit->cue-map
  "One TranslationUnit as the flat cue-map the reflow strategies shape. A
   translator's own line breaks are kept as lines, for a strategy to keep or
   redo."
  [unit]
  (let [[start-ms end-ms] (shared/range-ms (:range unit))]
    {:index 0 :start-ms start-ms :end-ms end-ms
     :lines (str/split-lines (str (:target-text unit)))}))

(defn- cue-map->cue [index cue-map]
  (rd/make-cue (assoc cue-map :index index)))

(defn build-subtitle-track
  "Turn a TranslatedCues aggregate into a render-ready SubtitleTrack: make the
   track, fill its cues (via the shared promote fold), seal it. `spec` =
   {:id :format :reflow}; language + source-id come from the TranslatedCues.

   With `:reflow` rules the units are first shaped as a whole track by the
   strategy the rules name (calc.reflow): lines broken for the TARGET language,
   which is only known here, and timing weighed across neighbours. Without
   them every unit is one cue with one line, as it always was.
   => (r/ok SubtitleTrack) | (r/err :error/render-failed ...)."
  [translated-cues {:keys [id format reflow]}]
  (r/let-ok [track  (rd/make-subtitle-track
                     {:id id
                      :source-id (:id translated-cues)
                      :language (:target-language translated-cues)
                      :format format})
             filled (if reflow
                      (r/let-ok [shaped (reflow/try-reflow
                                         (mapv unit->cue-map (:units translated-cues))
                                         (merge {:language (:target-language translated-cues)}
                                                reflow))]
                        (promote/fill-cues track cue-map->cue shaped))
                      (promote/fill-cues track unit->cue (:units translated-cues)))]
    (rd/render filled)))
