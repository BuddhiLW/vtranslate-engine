(ns vtranslate.engine.adapters.composer.softmux
  "IVideoComposer that SOFT-muxes a mov_text subtitle track into the container
   (selectable stream, audio+video stream-copied — no re-encode). Self-registers
   (defmethod resolve-composer :soft) — OCP.

   Loaded ONLY on the :ffmpeg classpath (delegates to collect.ffmpeg raw avformat)."
  (:require [hive-dsl.result :as r]
            [vtranslate.engine.port.composer :as p.comp]
            [vtranslate.engine.calc.overlay :as overlay]
            [vtranslate.engine.collect.ffmpeg :as ffmpeg]
            [vtranslate.engine.providers.composer-registry :as reg]
            [vtranslate.engine.calc.paths :as paths])
  (:import))

(defrecord SoftMuxComposer [opts]
  p.comp/IVideoComposer
  (compose [_ video-source subtitle-track compose-opts]
    (let [out  (or (:output-uri compose-opts)
                   (paths/sibling-output video-source ".subbed.mp4"))
          cues (overlay/timeline subtitle-track)
          lang (or (:language subtitle-track) "und")]
      (r/try-effect* :error/compose-failed
        (do (ffmpeg/soft-mux video-source out cues lang)
            {:output-uri out})))))

(defn make-composer
  "Build a SoftMuxComposer from config's :composer-opts."
  [config]
  (->SoftMuxComposer (get config :composer-opts {})))

(defmethod reg/resolve-composer :soft [_ config]
  (r/ok (make-composer config)))
