(ns vtranslate.engine.adapters.coverage.policies
  "The two built-in ICoveragePolicy records.

   `:grid-fill` (the default) keeps every span the segmenter reported — they
   carry its utterance boundaries, which a grid does not — and tiles whatever it
   left out, so whisper rather than the VAD decides what is speech. Measured
   2026-09-14 on the last three minutes of Sintel: silero accepted ONE span of
   1.7 s in 180 s of audio carrying five official cues, and the finished track
   held one cue; the same audio tiled by the grid produced 37.

   `:none` is the opt-out for a deployment that would rather lose speech than
   pay to decode music — it hands the inner spans back untouched.

   Both records are pure: the decision is a calculation over calc.coverage, and
   the only Result they can produce is ok. Failure is the registry's business."
  (:require [hive-dsl.result :as r]
            [vtranslate.engine.calc.coverage :as cov]
            [vtranslate.engine.port.coverage :as p.cov]
            [vtranslate.engine.providers.coverage-registry :as reg]))

(def default-window-ms
  "Window the uncovered stretches are tiled with: long enough that whisper has
   context to decode against, short enough to keep per-window overshoot and
   progress granularity in hand."
  15000)

(def default-min-gap-ms
  "Gaps shorter than this are left to the transcriber's own span padding, which
   already decodes a margin around every span; tiling them would only
   re-transcribe audio a neighbouring span carries."
  1000)

(defrecord GridFillPolicy [window-ms min-gap-ms]
  p.cov/ICoveragePolicy
  (cover [_ spans duration-ms]
    (r/ok (cov/fill-gaps spans duration-ms window-ms min-gap-ms))))

(defrecord PassThroughPolicy []
  p.cov/ICoveragePolicy
  (cover [_ spans _duration-ms]
    (r/ok (vec spans))))

(defn- positive-long [x fallback]
  (if (and (number? x) (pos? x)) (long x) fallback))

(defn make-grid-fill
  "opts = {:window-ms n (default 15000) :min-gap-ms n (default 1000)}."
  [{:keys [window-ms min-gap-ms]}]
  (->GridFillPolicy (positive-long window-ms default-window-ms)
                    (positive-long min-gap-ms default-min-gap-ms)))

(defmethod reg/resolve-coverage-policy :grid-fill
  [_ config]
  (let [{:keys [grid-fill-ms grid-fill-min-gap-ms]} (:segmenter-opts config)]
    (r/ok (make-grid-fill {:window-ms  grid-fill-ms
                           :min-gap-ms grid-fill-min-gap-ms}))))

(defmethod reg/resolve-coverage-policy :none
  [_ _config]
  (r/ok (->PassThroughPolicy)))
