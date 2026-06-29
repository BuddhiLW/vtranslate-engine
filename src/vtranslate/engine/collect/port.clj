(ns vtranslate.engine.collect.port
  "Anti-corruption bridge (hexagonal ports-and-adapters). The engine pipeline
   depends ONLY on the uniform media PORT (port.media). This adapter exposes the
   verified Collect backend through that port by delegating to the collect.audio
   orchestration sugar (fs existence check, ProbeInfo projection, boundary-owned
   temp policy) over an INJECTED collect.protocols backend.

   Layering (top depends down):
     api.run-job          -> port.media         (engine-facing port, domain-shaped)
     CollectMediaPort     -> collect.audio      (orchestration sugar / railway)
     collect.audio        -> collect.protocols  (Collect DIP anchor)
     collect.ffmpeg/JavaCvMedia                 (driven adapter, bytedeco)

   Loaded ONLY on the :ffmpeg classpath (it transitively requires collect.ffmpeg,
   which imports bytedeco). Loading this ns self-registers the :media provider via
   wiring/build-port (OCP) — the core engine stays loadable without bytedeco."
  (:require [hive-dsl.result :as r]
            [vtranslate.engine.port.media :as port]
            [vtranslate.engine.collect.audio :as audio]
            [vtranslate.engine.collect.ffmpeg :as ffmpeg]
            [vtranslate.engine.wiring :as wiring])
  (:import [java.io File]))

(defn- temp-wav-path
  "Boundary-owned temp sink for an extracted ASR track (removed at JVM exit).
   The port owns temp policy so the domain never names a filesystem path."
  ^String [_source-uri]
  (let [f (File/createTempFile "vtranslate-asr-" ".wav")]
    (.deleteOnExit f)
    (.getPath f)))

(defn- media-err
  "Remap a Collect/fs error onto the closed domain TranslationError ADT.
   => (r/err :error/source-unreadable | :error/probe-failed
             | :error/audio-extract-failed); unknown category passes through."
  [uri {cat :error :as err}]
  (let [reason (or (:reason err) (:message err) "")]
    (case cat
      (:collect/source-not-found :fs/check-failed)
      (r/err :error/source-unreadable {:source-uri (:source-uri err uri)})
      (:collect/probe-failed :collect/probe-timeout)
      (r/err :error/probe-failed {:reason reason})
      :collect/extract-failed
      (r/err :error/audio-extract-failed {:reason reason})
      err)))

(defrecord CollectMediaPort [backend]
  port/IMediaProbe
  (probe [_ uri]
    (r/map-err (audio/probe backend uri) #(media-err uri %)))
  port/IAudioExtractor
  (extract-audio [_ uri opts]
    (r/map-err
     (r/let-ok [out (r/try-effect* :collect/extract-failed (temp-wav-path uri))]
       (audio/extract-audio backend uri out (or opts {})))
     #(media-err uri %))))

(defn collect-media-port
  "Build the media port over the JavaCV collect backend (default) or an injected
   backend (e.g. a test double satisfying collect.protocols)."
  ([] (collect-media-port (ffmpeg/make-backend)))
  ([backend] (->CollectMediaPort backend)))

;; OCP: loading this ns (only under :ffmpeg) wires the :media provider. `config`
;; may carry :media-backend to inject a non-JavaCV backend.
(defmethod wiring/build-port :media
  [_port-key config]
  (r/ok (collect-media-port (or (:media-backend config) (ffmpeg/make-backend)))))
