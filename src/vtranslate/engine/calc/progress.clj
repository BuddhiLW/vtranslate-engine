(ns vtranslate.engine.calc.progress
  "Where a run is, as a percent, from work it can count.

   Each stage owns a band: from the percent it is entered at to the next
   stage's. A stage that can count its work (target languages translated,
   videos composed) reports how far into its band it is, so a long stage is
   not one number at the start and silence until the end.")

(def stage-floors
  "The percent each stage is entered at, in pipeline order."
  [[:ingesting 10]
   [:transcribing 25]
   [:translating 60]
   [:rendering 85]
   [:composing 95]
   [:completed 100]])

(defn floor-of
  "The percent `stage` is entered at, or nil for no stage."
  [stage]
  (some (fn [[s floor]] (when (= s stage) floor)) stage-floors))

(defn ceiling-of
  "The percent the stage after `stage` is entered at; 100 for the last."
  [stage]
  (or (->> stage-floors
           (drop-while (fn [[s]] (not= s stage)))
           second
           second)
      100))

(defn within-band
  "The percent for `done` of `total` units of `stage`'s work.

   Never below the stage's floor and never at the next stage's: finishing a
   stage's counted work is not the same as the next stage having started."
  [stage done total]
  (let [floor   (floor-of stage)
        ceiling (ceiling-of stage)]
    (if (and floor (pos? total))
      (-> (+ floor (quot (* (- ceiling floor) (max 0 (min done total))) total))
          (min (dec ceiling))
          (max floor))
      floor)))
