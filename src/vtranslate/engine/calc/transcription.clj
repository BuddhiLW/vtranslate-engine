(ns vtranslate.engine.calc.transcription
  "Promote (CPPB) — pure: lift boundary ASR segment-data into a Transcript
   aggregate. No IO; the effects already happened in the ITranscriber adapter."
  (:require [hive-dsl.result :as r]
            [vtranslate.engine.domain.transcription :as tx]
            [vtranslate.engine.shared :as shared]))

(def ^:private undetermined
  "Language tags that name no language."
  #{nil "" "auto" "multi" "und"})

(defn detected-language
  "The source-registry language `segments` themselves name, or nil when none
   does: the most frequent tag among them, ties going to the one heard first.
   A tag outside `shared/source-languages` is not evidence.
   => tag | nil"
  [segments]
  (let [tags (into []
                   (comp (map :language)
                         (remove undetermined)
                         (keep #(shared/canonical-tag shared/source-languages %)))
                   segments)]
    (when (seq tags)
      (let [first-at (reduce (fn [m [i tag]] (if (contains? m tag) m (assoc m tag i)))
                             {}
                             (map-indexed vector tags))]
        (key (first (sort-by (fn [[tag n]] [(- n) (first-at tag)])
                             (frequencies tags))))))))

(defn build-transcript
  "Fold ASR segment-data maps into a completed Transcript. Boundary ASR data has
   no :index (adapters don't number segments); the promote layer assigns the
   1-based index. Missing per-segment language defaults to transcript language.

   An UNDETERMINED `:language` is resolved from the segments' own tags when any
   of them carries one (`detected-language`), so an auto-detect job records the
   language that was heard instead of \"und\".

   NO segments seals a SILENT transcript rather than failing: media with no
   speech in it (a music-only film, a silent clip) is a job that finished, not
   an ASR failure. Callers distinguish the two with `transcription/silent?`.
   => (r/ok Transcript) | (r/err ...)."
  [{:keys [id asset-id language segments]}]
  (let [language (if (contains? undetermined language)
                   (or (detected-language segments) language)
                   language)]
    (r/let-ok [t0     (tx/make-transcript {:id id :asset-id asset-id :language language})
               filled (reduce (fn [acc seg-data]
                                (r/let-ok [t   acc
                                           seg (tx/make-segment seg-data)]
                                  (r/ok (tx/add-segment t seg))))
                              (r/ok t0)
                              (map-indexed (fn [i seg]
                                             (assoc seg
                                                    :index (inc i)
                                                    :language (or (:language seg) language)))
                                           segments))]
      (if (seq (:segments filled))
        (tx/complete filled)
        (tx/seal-silent filled)))))
