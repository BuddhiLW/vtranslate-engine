(ns vtranslate.engine.adapters.segmenter.stub
  "Reference ISegmenter adapter — a deterministic fixed-window grid cutter. Tiles
   [0, duration-ms) into :window-ms spans (final span clamped to duration-ms). No
   media IO, so it is the test double / fallback implementation behind the
   segmenter port. Also owns segmenter selection for the :segmenter wiring seam:
   default :grid, :none disables cutting, :silero-vad delegates to the optional
   Silero adapter without importing its ONNX classes.
   Registers itself under wiring/build-port :segmenter (OCP)."
  (:require [hive-dsl.result :as r]
            [vtranslate.engine.port.segmenter :as p.seg]
            [vtranslate.engine.providers.config :as cfg]
            [vtranslate.engine.wiring :as wiring]
            [vtranslate.engine.providers.segmenter-registry :as reg]))

(defn grid-spans
  "Pure: tile [0, duration-ms) into contiguous windows of window-ms, the final
   span clamped to duration-ms. Non-positive duration or window => [].
   => [{:start-ms n :end-ms n} ...] ordered, non-overlapping, contiguous."
  [duration-ms window-ms]
  (if (or (not (pos? duration-ms)) (not (pos? window-ms)))
    []
    (mapv (fn [start] {:start-ms start :end-ms (min (+ start window-ms) duration-ms)})
          (range 0 duration-ms window-ms))))

(defrecord GridSegmenter [window-ms]
  p.seg/ISegmenter
  (segment [_ audio-source opts]
    (let [duration-ms (or (:duration-ms opts) (:duration-ms audio-source) 0)]
      (r/ok {:spans (grid-spans duration-ms window-ms)}))))

(defn make-segmenter
  "Build a GridSegmenter. Default window 5000ms."
  ([] (make-segmenter 5000))
  ([window-ms] (->GridSegmenter window-ms)))

(defmethod reg/resolve-segmenter :grid
  [_ config]
  (r/ok (make-segmenter (get config :segment-window-ms 5000))))

(defmethod wiring/build-port :segmenter
  [_ config]
  (r/let-ok [routing (cfg/resolve-routing config)]
    (let [segmenter (:segmenter routing)]
      (if (contains? #{nil :none} segmenter)
        (r/ok nil)
        (reg/resolve-segmenter segmenter (merge config routing))))))