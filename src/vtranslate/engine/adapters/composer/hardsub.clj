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
            [vtranslate.engine.calc.paths :as paths])
  (:import))

(defn- lines-at-fn
  "Close a rendered SubtitleTrack + opts into (fn [t-ms] -> [line ...] | nil): the
   active cue's lines at a frame timestamp, optionally word-wrapped to :wrap chars."
  [track {:keys [wrap]}]
  (let [tl (overlay/timeline track)]
    (fn [t-ms]
      (when-let [lines (overlay/active-lines tl t-ms)]
        (if wrap
          (vec (mapcat #(overlay/wrap-line % wrap) lines))
          lines)))))

(defrecord HardsubComposer [opts]
  p.comp/IVideoComposer
  (compose [_ video-source subtitle-track compose-opts]
    (let [out      (or (:output-uri compose-opts)
                       (paths/sibling-output video-source ".subbed.mp4"))
          lines-at (lines-at-fn subtitle-track opts)]
      (r/try-effect* :error/compose-failed
        (do (support/atomically out #(ffmpeg/burn-hardsub video-source % lines-at opts))
            {:output-uri out})))))

(def default-wrap
  "Characters per subtitle line before wrapping. Without a wrap a long cue is
   drawn as one full-width line that runs off both edges."
  42)

(defn make-composer
  "Build a HardsubComposer from config's :composer-opts (:font-size, :wrap).
   :font-size absent => scaled to the frame height; :wrap absent => `default-wrap`."
  [config]
  (->HardsubComposer (merge {:wrap default-wrap} (get config :composer-opts {}))))

(defmethod reg/resolve-composer :hard [_ config]
  (r/ok (make-composer config)))
