(ns vtranslate.engine.adapters.codec.dispatch
  "Registry-backed subtitle codec: ONE adapter that satisfies BOTH subtitle ports
   by looking the per-format codec up in the codec registry and delegating. This
   is the seam the pipeline injects as :renderer / :subtitle-parser — a consumer
   holds a single impl, but the concrete SRT/VTT codec is selected per call by the
   SubtitleTrack's format (render) or the requested format (parse). Adding a
   format is a registry entry, never an edit here (OCP). Self-registers under
   wiring/build-port :renderer + :subtitle-parser."
  (:require [hive-dsl.result :as r]
            [vtranslate.engine.adapters.codec.registry :as reg]
            [vtranslate.engine.port.subtitle :as p.sub]
            [vtranslate.engine.wiring :as wiring]))

(defn- format-of
  "The track's SubtitleFormat as its plain registry keyword. Tolerates both the
   defadt-wrapped value (make-subtitle-track) and a raw keyword."
  [track]
  (get-in track [:format :adt/variant] (:format track)))

(defrecord RegistryCodec [registry]
  p.sub/ISubtitleRenderer
  (render-bytes [_ track]
    (r/let-ok [codec (reg/codec-for registry (format-of track))]
      (p.sub/render-bytes codec track)))

  p.sub/ISubtitleParser
  (parse [_ text format]
    (r/let-ok [codec (reg/codec-for registry format)]
      (p.sub/parse codec text format))))

(defn make-codec
  "Registry-backed codec over the built-in format set (extend via reg/register)."
  ([] (make-codec reg/default-registry))
  ([registry] (->RegistryCodec registry)))

(defmethod wiring/build-port :renderer
  [_ _config]
  (r/ok (make-codec)))

(defmethod wiring/build-port :subtitle-parser
  [_ _config]
  (r/ok (make-codec)))
