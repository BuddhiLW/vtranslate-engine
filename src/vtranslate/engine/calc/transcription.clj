(ns vtranslate.engine.calc.transcription
  "Promote (CPPB) — pure: lift boundary ASR segment-data into a Transcript
   aggregate. No IO; the effects already happened in the ITranscriber adapter."
  (:require [hive-dsl.result :as r]
            [vtranslate.engine.domain.transcription :as tx]
            [vtranslate.engine.calc.asr-hygiene :as hygiene]))

(defn build-transcript
  "Fold ASR segment-data maps into a completed Transcript. Boundary ASR data has
   no :index (adapters don't number segments); the promote layer assigns the
   1-based index. Missing per-segment language defaults to transcript language.
   Decoder placeholders are marked (asr-hygiene/mark-placeholder), never removed.

   NO segments seals a SILENT transcript rather than failing: media with no
   speech in it (a music-only film, a silent clip) is a job that finished, not
   an ASR failure. Callers distinguish the two with `transcription/silent?`.
   => (r/ok Transcript) | (r/err ...)."
  [{:keys [id asset-id language segments]}]
  (r/let-ok [t0     (tx/make-transcript {:id id :asset-id asset-id :language language})
             filled (reduce (fn [acc seg-data]
                              (r/let-ok [t   acc
                                         seg (tx/make-segment seg-data)]
                                (r/ok (tx/add-segment t seg))))
                            (r/ok t0)
                            (map-indexed (fn [i seg]
                                           (-> seg
                                               hygiene/mark-placeholder
                                               (assoc :index (inc i)
                                                      :language (or (:language seg) language))))
                                         segments))]
    (if (seq (:segments filled))
      (tx/complete filled)
      (tx/seal-silent filled))))
