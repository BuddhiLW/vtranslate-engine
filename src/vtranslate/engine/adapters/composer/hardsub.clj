(ns vtranslate.engine.adapters.composer.hardsub
  "IVideoComposer that BURNS subtitle cues into the video picture (hardsub),
   re-encoding H.264/AAC. Self-registers (defmethod resolve-composer :hard).

   Two burn backends, chosen once at construction by calc.burn from
   :composer-opts: the system ffmpeg (libass + libx264, one subprocess) when
   a binary answers, else the in-process JavaCV boundary. Loaded ONLY on the
   :ffmpeg classpath (delegates to collect.ffmpeg, which imports bytedeco)."
  (:require [hive-dsl.result :as r]
            [vtranslate.engine.port.composer :as p.comp]
            [vtranslate.engine.adapters.composer.support :as support]
            [vtranslate.engine.calc.burn :as burn]
            [vtranslate.engine.calc.overlay :as overlay]
            [vtranslate.engine.collect.ffmpeg :as ffmpeg]
            [vtranslate.engine.collect.ffmpeg-cli :as cli]
            [vtranslate.engine.providers.composer-registry :as reg]
            [vtranslate.engine.calc.paths :as paths]
            [vtranslate.engine.calc.captions :as captions]))

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

(defn- javacv-burn!
  "(fn [video-source out track style]) over the in-process JavaCV path."
  [video-source out track style]
  (ffmpeg/burn-hardsub video-source out (lines-at-fn track style) style))

(defn- cli-burn!
  "(fn [video-source out track style]) over the system ffmpeg at `bin`. The
   CLI path wraps text itself from the same :wrap, so it takes the plain
   timeline rather than the per-frame closure."
  [bin]
  (fn [video-source out track style]
    (cli/burn-hardsub bin video-source out (overlay/timeline track) style)))

(defrecord HardsubComposer [opts backend burn!]
  p.comp/IVideoComposer
  (compose [_ video-source subtitle-track compose-opts]
    ;; Per-job style wins over the deployment's defaults: caption size and
    ;; placement are a property of the video being made, not of the pod.
    (let [style (merge opts (dissoc compose-opts :output-uri))
          out   (or (:output-uri compose-opts)
                    (paths/sibling-output video-source ".subbed.mp4"))]
      (r/try-effect* :error/compose-failed
        (do (support/atomically out #(burn! video-source % subtitle-track style))
            {:output-uri out})))))

(defn make-composer
  "Build a HardsubComposer from config's :composer-opts. Anything absent is
   filled by calc.captions defaults at draw time. The backend is probed here,
   once per wiring, not per job: `:backend` on the record says which won."
  [config]
  (let [opts    (get config :composer-opts {})
        bin     (burn/binary opts)
        backend (burn/choose opts (and (= :auto (burn/requested opts))
                                       (cli/available? bin)))]
    (->HardsubComposer opts backend
                       (case backend
                         :ffmpeg-cli (cli-burn! bin)
                         :javacv     javacv-burn!))))

(defmethod reg/resolve-composer :hard [_ config]
  (r/ok (make-composer config)))
