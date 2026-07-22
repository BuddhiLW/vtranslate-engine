(ns vtranslate.engine.collect.port
  "Bytedeco-coupled half of the media anti-corruption bridge. The pure adapter
   (CollectMediaPort + the media-err remap) lives in collect.media-port; this ns
   only supplies the DEFAULT JavaCV backend and self-registers the :media
   provider via wiring/build-port (OCP).

   Layering (top depends down):
     api.run-job          -> port.media           (engine-facing port, domain-shaped)
     CollectMediaPort     -> collect.audio         (orchestration sugar / railway)
     collect.audio        -> collect.protocols     (Collect DIP anchor)
     collect.ffmpeg/JavaCvMedia                    (driven adapter, bytedeco)

   Loaded ONLY on the :ffmpeg classpath (it transitively requires collect.ffmpeg,
   which imports bytedeco). Loading this ns self-registers the :media provider —
   the core engine stays loadable without bytedeco, and the pure bridge stays
   testable without it (collect.media-port)."
  (:require [hive-dsl.result :as r]
            [vtranslate.engine.collect.media-port :as mp]
            [vtranslate.engine.collect.ffmpeg :as ffmpeg]
            [vtranslate.engine.wiring :as wiring]))

(defn collect-media-port
  "Build the media port over the JavaCV collect backend (default) or an injected
   backend (e.g. a test double satisfying collect.protocols)."
  ([] (mp/collect-media-port (ffmpeg/make-backend)))
  ([backend] (mp/collect-media-port backend)))

;; OCP: loading this ns (only under :ffmpeg) wires the :media provider. `config`
;; may carry :media-backend to inject a non-JavaCV backend.
(defmethod wiring/build-port :media
  [_port-key config]
  (r/ok (collect-media-port (or (:media-backend config) (ffmpeg/make-backend)))))
