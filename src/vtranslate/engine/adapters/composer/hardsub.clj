(ns vtranslate.engine.adapters.composer.hardsub
  "IVideoComposer that BURNS subtitle cues into the video picture (hardsub),
   re-encoding H.264/AAC via the in-process JavaCV boundary. Self-registers
   (defmethod resolve-composer :hard) — OCP.

   Loaded ONLY on the :ffmpeg classpath (delegates to collect.ffmpeg, which
   imports bytedeco)."
  (:require [hive-dsl.result :as r]
            [vtranslate.engine.port.composer :as p.comp]
            [vtranslate.engine.adapters.composer.support :as support]
            [vtranslate.engine.calc.overlay :as overlay]
            [vtranslate.engine.collect.ffmpeg :as ffmpeg]
            [vtranslate.engine.providers.composer-registry :as reg]
            [vtranslate.engine.calc.paths :as paths]
            [vtranslate.engine.calc.captions :as captions])
  (:import))

(defn- lines-at-fn
  "Close a rendered SubtitleTrack + opts into (fn [t-ms] -> [line ...] | nil): the
   active cue's lines at a frame timestamp, word-wrapped to the style's :wrap."
  [track opts]
  (let [tl   (overlay/timeline track)
        wrap (:wrap (captions/style opts))]
    (fn [t-ms]
      (when-let [lines (overlay/active-lines tl t-ms)]
        (if wrap
          (vec (mapcat #(overlay/wrap-line % wrap) lines))
          lines)))))

(defrecord HardsubComposer [opts]
  p.comp/IVideoComposer
  (compose [_ video-source subtitle-track compose-opts]
    ;; Per-job style wins over the deployment's defaults: caption size and
    ;; placement are a property of the video being made, not of the pod.
    (let [style    (merge opts (dissoc compose-opts :output-uri))
          out      (or (:output-uri compose-opts)
                       (paths/sibling-output video-source ".subbed.mp4"))
          lines-at (lines-at-fn subtitle-track style)]
      (r/try-effect* :error/compose-failed
        (do (support/atomically out #(ffmpeg/burn-hardsub video-source % lines-at style))
            {:output-uri out})))))

(defn make-composer
  "Build a HardsubComposer from config's :composer-opts. Anything absent is
   filled by calc.captions defaults at draw time."
  [config]
  (->HardsubComposer (get config :composer-opts {})))

(defmethod reg/resolve-composer :hard [_ config]
  (r/ok (make-composer config)))
