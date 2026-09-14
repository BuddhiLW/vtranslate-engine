(ns vtranslate.engine.adapters.segmenter.gap-filled
  "ISegmenter DECORATOR: run the inner segmenter, then hand its spans to an
   ICoveragePolicy.

   The decorator itself decides nothing — it depends on the two PORTS and on
   neither implementation (DIP), so the same object wraps silero, the grid, or
   an addon's segmenter, under whichever coverage policy the config named. The
   substitution is total: it satisfies ISegmenter, returns spans in the same
   shape, and a policy that passes its input through leaves the inner
   segmenter's behaviour exactly as it was (LSP).

   The cost, when the policy does fill: a clip whose VAD accepts nothing is
   decoded end to end — the same work :grid would have done."
  (:require [hive-dsl.result :as r]
            [vtranslate.engine.port.coverage :as p.cov]
            [vtranslate.engine.port.segmenter :as p.seg]
            [vtranslate.engine.providers.coverage-registry :as reg]))

(def default-policy-key
  "Silent loss is worse than an honest decode bill, so coverage is filled unless
   a deployment says otherwise with [:segmenter-opts :coverage] :none."
  :grid-fill)

(defn- duration-of
  "The probed media length the policy needs, or 0 when nothing carries it — a
   policy told 0 must pass the spans through, because it cannot know what is
   uncovered."
  [audio-source opts]
  (long (or (:duration-ms opts) (:duration-ms audio-source) 0)))

(defrecord GapFilledSegmenter [inner policy]
  p.seg/ISegmenter
  (segment [_ audio-source opts]
    (r/let-ok [{:keys [spans]} (p.seg/segment inner audio-source opts)
               covered         (p.cov/cover policy spans (duration-of audio-source opts))]
      (r/ok {:spans covered}))))

(defn make-gap-filled
  "Decorate `inner` ISegmenter with `policy`."
  [inner policy]
  (->GapFilledSegmenter inner policy))

(defn wrap
  "Wrap `inner` with the coverage policy named by [:segmenter-opts :coverage]
   (default :grid-fill). :none resolves to the pass-through policy, which is
   still a wrap — the seam stays in place, so a deployment can change its mind
   in config rather than in code.
   => (r/ok ISegmenter) | (r/err :error/unknown-coverage-policy ...)."
  [inner config]
  (let [policy-key (get-in config [:segmenter-opts :coverage] default-policy-key)]
    (r/let-ok [policy (reg/resolve-coverage-policy policy-key config)]
      (r/ok (make-gap-filled inner policy)))))
