(ns vtranslate.engine.calc.subtitle-out
  "Promote (CPPB) — pure: the translate seam for the no-ASR ingress. Extract the
   translatable text of each parsed cue-map (a stable, order-preserving view the
   ITranslator batch consumes) and fold the returned translations back onto the
   cue-maps 1:1. Pure + total except the explicit fail-loud on a count mismatch:
   a translator that drops/reorders segments must never silently corrupt timing."
  (:require [clojure.string :as str]
            [hive-dsl.result :as r]))

(defn cue-texts
  "Translatable text per cue-map, cue order preserved: each cue's lines joined
   with newlines into one string (the unit the ITranslator batch translates)."
  [cue-maps]
  (mapv (fn [{:keys [lines]}] (str/join "\n" lines)) cue-maps))

(defn apply-translations
  "Fold `translations` (1:1 with `cue-maps`, same order) back onto the cue-maps,
   replacing each cue's :lines with the translated text split into lines; timing
   + index carry through untouched. Fails loud when the counts disagree so a
   misbehaving translator can never desync text from timing.
   => (r/ok [cue-map ...]) | (r/err :error/translation-failed {:reason s})."
  [cue-maps translations]
  (if (not= (count cue-maps) (count translations))
    (r/err :error/translation-failed
           {:reason (format "translation count %d != cue count %d"
                            (count translations) (count cue-maps))})
    (r/ok (mapv (fn [cue tr] (assoc cue :lines (str/split-lines (str tr))))
                cue-maps translations))))
