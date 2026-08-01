(ns vtranslate.engine.providers.compatibility
  "L2 — routing PAIRING rules. A transcriber declares the segmentation it
   REQUIRES; a segmenter declares what it PRODUCES; `check` refuses a pairing
   that cannot preserve speech. Open (OCP): an adapter registers its own
   defmethod rather than editing this ns."
  (:require [hive-dsl.result :as r]))

(defmulti segmentation-required
  "Segmentation `transcriber-key` requires. => :utterance | :any"
  identity)

(defmethod segmentation-required :default [_] :any)

(defmulti segmentation-produced
  "Segmentation `segmenter-key` produces. => :utterance | :fixed-window | :unknown"
  identity)

(defmethod segmentation-produced :default [_] :unknown)

(defn incompatible?
  "True only when a transcriber requiring utterance boundaries is paired with a
   segmenter DECLARED to cut fixed windows. An undeclared segmenter is never
   refused."
  [transcriber-key segmenter-key]
  (and (= :utterance (segmentation-required transcriber-key))
       (= :fixed-window (segmentation-produced segmenter-key))))

(defn check
  "Validate the transcriber/segmenter pairing in `routing`.
   => (r/ok routing) | (r/err :error/incompatible-routing
                              {:transcriber kw :segmenter kw :hint str})."
  [routing]
  (let [{:keys [transcriber segmenter]} routing]
    (if (incompatible? transcriber segmenter)
      (r/err :error/incompatible-routing
             {:transcriber transcriber
              :segmenter   segmenter
              :hint        (str "the " transcriber " provider decodes whole utterances and returns an"
                                " EMPTY hypothesis for a window cut mid-utterance, silently losing"
                                " that window's speech; route an utterance segmenter"
                                " (VT_SEGMENTER=silero-vad) instead of " segmenter)})
      (r/ok routing))))
