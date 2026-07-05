(ns vtranslate.engine.calc.subtitle-in
  "Promote (CPPB) — pure: lift parsed subtitle cue-maps (the no-ASR ingress —
   ISubtitleParser output) into a render-ready SubtitleTrack aggregate. This is
   the subtitle-in seam feeding the parse -> translate -> re-render path. Parser
   :index values are unreliable (0 on unparseable index lines, non-monotonic /
   duplicated in real corpora), so cues are re-numbered 1-based in input order —
   the same enrichment rule the transcription + rendering promoters apply. No IO;
   the parse effect already happened in the ISubtitleParser adapter."
  (:require [hive-dsl.result :as r]
            [vtranslate.engine.domain.rendering :as rd]))

(defn- cue-map->cue
  "Promote one parsed cue-map into a domain Cue at the given 1-based index. The
   parser cue-map is {:index :start-ms :end-ms :lines}; only :start-ms/:end-ms/
   :lines are used (index is re-assigned by the promote layer).
   => (r/ok Cue) | (r/err :error/render-failed ...)."
  [index {:keys [start-ms end-ms lines]}]
  (rd/make-cue {:index index :start-ms start-ms :end-ms end-ms :lines lines}))

(defn- add-cue-map
  "Reducing step over a Result-threaded track: append the parsed cue-map as the
   Cue at `index`, short-circuiting on the first error."
  [track-result [index cue-map]]
  (r/let-ok [track track-result
             cue   (cue-map->cue index cue-map)]
    (r/ok (rd/add-cue track cue))))

(defn- fill-cues
  "Fold parsed `cue-maps` onto `track` as 1-based Cues, preserving input order.
   => (r/ok SubtitleTrack) | (r/err ...)."
  [track cue-maps]
  (->> cue-maps
       (map-indexed (fn [i cue-map] [(inc i) cue-map]))
       (reduce add-cue-map (r/ok track))))

(defn build-subtitle-track
  "Promote parsed subtitle cue-maps into a render-ready SubtitleTrack (no ASR).
   `cue-maps` = the ISubtitleParser :cues data [{:index :start-ms :end-ms :lines}
   ...] (parser :index ignored — re-numbered 1-based in order); `spec` =
   {:id :source-id :language :format}. Empty cue-maps (or a cue with no text
   lines) fails loud, matching the domain make-cue / render contract.
   => (r/ok SubtitleTrack)
    | (r/err :error/render-failed | :error/unsupported-format
             | :error/unsupported-language ...)."
  [cue-maps {:keys [id source-id language format]}]
  (r/let-ok [track  (rd/make-subtitle-track {:id id
                                             :source-id source-id
                                             :language language
                                             :format format})
             filled (fill-cues track cue-maps)]
    (rd/render filled)))
