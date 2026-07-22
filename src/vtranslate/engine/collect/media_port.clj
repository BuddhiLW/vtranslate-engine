(ns vtranslate.engine.collect.media-port
  "Anti-corruption bridge (hexagonal ports-and-adapters), bytedeco-FREE.
   CollectMediaPort adapts an INJECTED collect.protocols backend to the
   engine-facing port.media, remapping Collect/fs error categories onto the
   closed domain TranslationError ADT (media-err) before they cross the barrier.

   The bytedeco-coupled half — the default JavaCV backend construction and the
   OCP :media provider wiring — lives in collect.port. Keeping the pure bridge
   here means media-err + the port contract are testable with a stub backend,
   no native classpath required."
  (:require [hive-dsl.result :as r]
            [vtranslate.engine.port.media :as port]
            [vtranslate.engine.collect.audio :as audio])
  (:import [java.io File]))

(defn- temp-wav-path
  "Boundary-owned temp sink for an extracted ASR track (removed at JVM exit).
   The port owns temp policy so the domain never names a filesystem path."
  ^String [_source-uri]
  (let [f (File/createTempFile "vtranslate-asr-" ".wav")]
    (.deleteOnExit f)
    (.getPath f)))

(defn media-err
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
  "Build the media port over an INJECTED collect.protocols backend (a test double
   or the JavaCV backend supplied by collect.port)."
  [backend]
  (->CollectMediaPort backend))
