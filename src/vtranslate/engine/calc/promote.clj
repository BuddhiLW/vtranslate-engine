(ns vtranslate.engine.calc.promote
  "Promote (CPPB) — the shared cue-fill fold behind every subtitle promoter
   (translation->render and parsed subtitle-in). Re-numbers items 1-based in
   input order (segment / parser indices are unreliable) and appends each as a
   domain Cue, short-circuiting on the first error. No IO."
  (:require [hive-dsl.result :as r]
            [vtranslate.engine.domain.rendering :as rd]))

(defn fill-cues
  "Fold `items` onto `track` as 1-based Cues, preserving input order. `item->cue`
   promotes one item at its 1-based index => (r/ok Cue) | (r/err ...). Short-
   circuits on the first error.
   => (r/ok SubtitleTrack) | (r/err ...)."
  [track item->cue items]
  (->> items
       (map-indexed (fn [i item] [(inc i) item]))
       (reduce (fn [track-result [index item]]
                 (r/let-ok [track track-result
                            cue   (item->cue index item)]
                   (r/ok (rd/add-cue track cue))))
               (r/ok track))))
