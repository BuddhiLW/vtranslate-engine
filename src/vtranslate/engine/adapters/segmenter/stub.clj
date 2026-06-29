(ns vtranslate.engine.adapters.segmenter.stub
  "Reference ISegmenter adapter — a deterministic fixed-window grid cutter. Tiles
   [0, duration-ms) into :window-ms spans (final span clamped to duration-ms). No
   VAD/ML; it is the conformant stub the engine wires until a real VAD adapter
   lands, and the substitutable impl the port contract runs against (Liskov).
   Registers itself under wiring/build-port :segmenter (OCP)."
  (:require [hive-dsl.result :as r]
            [vtranslate.engine.port.segmenter :as p.seg]
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
  (r/ok (make-segmenter (get config :segment-window-ms 5000))))
