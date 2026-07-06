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
            [vtranslate.engine.wiring :as wiring]))

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

(defmethod wiring/build-port :segmenter
  [_ config]
  (r/let-ok [routing (cfg/resolve-routing config)]
    (case (:segmenter routing)
      :grid
      (r/ok (make-segmenter (get config :segment-window-ms 5000)))

      :none
      (r/ok nil)

      :silero-vad
      (if-let [make-silero (requiring-resolve
                            (quote vtranslate.engine.adapters.segmenter.silero-vad/make-segmenter))]
        (make-silero (merge config routing))
        (r/err :error/segmentation-failed
               {:reason "silero-vad segmenter namespace is not loadable"}))

      (r/err :error/segmentation-failed
             {:reason (str "unknown segmenter provider: " (:segmenter routing))}))))
