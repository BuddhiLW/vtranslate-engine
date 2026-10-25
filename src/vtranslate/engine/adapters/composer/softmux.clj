(ns vtranslate.engine.adapters.composer.softmux
  "IVideoComposer that SOFT-muxes a mov_text subtitle track into the container
   (selectable stream, audio+video stream-copied — no re-encode). Self-registers
   (defmethod resolve-composer :soft) — OCP.

   Loaded ONLY on the :ffmpeg classpath (delegates to collect.ffmpeg raw avformat)."
  (:require [hive-dsl.result :as r]
            [vtranslate.engine.port.composer :as p.comp]
            [vtranslate.engine.adapters.composer.support :as support]
            [vtranslate.engine.calc.overlay :as overlay]
            [vtranslate.engine.collect.ffmpeg :as ffmpeg]
            [vtranslate.engine.providers.composer-registry :as reg]
            [vtranslate.engine.calc.paths :as paths])
  (:import [java.util Locale MissingResourceException]))

(defn container-language
  "The ISO 639-2 code the mp4 muxer stores for a track's BCP-47 `tag`
   (\"pt-BR\" -> \"por\", \"en\" -> \"eng\"). The muxer keeps ONLY three-letter
   codes and silently drops anything else, and a SubtitleTrack's language is
   always BCP-47, so passing it through unconverted lost every real tag. A tag
   with no ISO 639-2 equivalent (or nil/blank) => \"und\"."
  [tag]
  (let [code (try
               (some-> tag str not-empty Locale/forLanguageTag .getISO3Language)
               (catch MissingResourceException _ nil))]
    (if (seq code) code "und")))

(defrecord SoftMuxComposer [opts]
  p.comp/IVideoComposer
  (compose [_ video-source subtitle-track compose-opts]
    (let [out  (or (:output-uri compose-opts)
                   (paths/sibling-output video-source ".subbed.mp4"))
          cues (overlay/timeline subtitle-track)
          lang (container-language (:language subtitle-track))]
      (r/try-effect* :error/compose-failed
        (do (support/atomically out #(ffmpeg/soft-mux video-source % cues lang))
            {:output-uri out})))))

(defn make-composer
  "Build a SoftMuxComposer from config's :composer-opts."
  [config]
  (->SoftMuxComposer (get config :composer-opts {})))

(defmethod reg/resolve-composer :soft [_ config]
  (r/ok (make-composer config)))
