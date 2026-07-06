(ns vtranslate.engine.adapters.composer.softmux
  "IVideoComposer that SOFT-muxes a mov_text subtitle track into the container
   (selectable stream, audio+video stream-copied — no re-encode). Self-registers
   (defmethod resolve-composer :soft) — OCP.

   Loaded ONLY on the :ffmpeg classpath (delegates to collect.ffmpeg raw avformat)."
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

(defrecord SoftMuxComposer [opts]
  p.comp/IVideoComposer
  (compose [_ video-source subtitle-track compose-opts]
    (let [out  (or (:output-uri compose-opts) (default-output video-source))
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
