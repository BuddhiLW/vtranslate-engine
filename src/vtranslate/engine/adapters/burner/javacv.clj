(ns vtranslate.engine.adapters.burner.javacv
  "IHardsubBurner over the in-process JavaCV boundary: Java2D draws each
   captioned frame, the bundled encoder re-encodes. Needs no binary and is
   several times slower than the ffmpeg CLI at 1080p, so it is the floor
   :auto falls back to. Self-registers (defmethod resolve-burner :javacv).

   Loaded ONLY on the :ffmpeg classpath (delegates to collect.ffmpeg, which
   imports bytedeco)."
  (:require [hive-dsl.result :as r]
            [vtranslate.engine.calc.captions :as captions]
            [vtranslate.engine.calc.overlay :as overlay]
            [vtranslate.engine.collect.ffmpeg :as ffmpeg]
            [vtranslate.engine.port.burner :as p.burner]
            [vtranslate.engine.providers.burner-registry :as reg]))

(defn- lines-at-fn
  "Close a rendered SubtitleTrack + style into (fn [t-ms] -> [line ...] | nil):
   the active cue's lines at a frame timestamp, word-wrapped to `:wrap`."
  [track style]
  (let [tl   (overlay/timeline track)
        wrap (:wrap (captions/style style))]
    (fn [t-ms]
      (when-let [lines (overlay/active-lines tl t-ms)]
        (if wrap
          (vec (mapcat #(overlay/wrap-line % wrap) lines))
          lines)))))

(defrecord JavaCvBurner []
  p.burner/IHardsubBurner
  (burn! [_ video-source out-path track style]
    (ffmpeg/burn-hardsub video-source out-path (lines-at-fn track style) style)))

(defmethod reg/resolve-burner :javacv [_ _opts]
  (r/ok (->JavaCvBurner)))
