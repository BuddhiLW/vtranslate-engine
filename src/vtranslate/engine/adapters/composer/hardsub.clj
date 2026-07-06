(ns vtranslate.engine.adapters.composer.hardsub
  "IVideoComposer that BURNS subtitle cues into the video picture (hardsub),
   re-encoding H.264/AAC via the in-process JavaCV boundary. Self-registers
   (defmethod resolve-composer :hard) — OCP.

   Loaded ONLY on the :ffmpeg classpath (delegates to collect.ffmpeg, which
   imports bytedeco)."
  (:require [hive-dsl.result :as r]
            [vtranslate.engine.port.composer :as p.comp]
            [vtranslate.engine.calc.overlay :as overlay]
            [vtranslate.engine.collect.ffmpeg :as ffmpeg]
            [vtranslate.engine.providers.composer-registry :as reg])
  (:import [java.io File]))

(defn- default-output
  "mp4 sink beside the source video (…/name.subbed.mp4) when the caller names none."
  ^String [^String source-uri]
  (let [f      (File. source-uri)
        parent (.getParent f)
        name   (.getName f)
        dot    (.lastIndexOf name ".")
        base   (if (pos? dot) (subs name 0 dot) name)]
    (str (when parent (str parent File/separator)) base ".subbed.mp4")))

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
    (let [out      (or (:output-uri compose-opts) (default-output video-source))
          lines-at (lines-at-fn subtitle-track opts)]
      (r/try-effect* :error/compose-failed
        (do (ffmpeg/burn-hardsub video-source out lines-at opts)
            {:output-uri out})))))

(defn make-composer
  "Build a HardsubComposer from config's :composer-opts (:font-size, :wrap)."
  [config]
  (->HardsubComposer (get config :composer-opts {})))

(defmethod reg/resolve-composer :hard [_ config]
  (r/ok (make-composer config)))
