(ns vtranslate.engine.adapters.codec.registry
  "Open format->codec registry (OCP). A new subtitle format is added by binding
   its codec under the SubtitleFormat variant keyword — never by editing a
   dispatch in core. The registry is a plain immutable map; `register` returns a
   new one (no global mutable state), so a caller can extend the default set."
  (:require [hive-dsl.result :as r]
            [vtranslate.engine.adapters.codec.srt :as srt]
            [vtranslate.engine.adapters.codec.vtt :as vtt]))

(def default-registry
  "Built-in codecs keyed by SubtitleFormat variant keyword."
  {:format/srt (srt/make-codec)
   :format/vtt (vtt/make-codec)})

(defn register
  "Return `registry` with `codec` bound to `format` (pure assoc)."
  [registry format codec]
  (assoc registry format codec))

(defn codec-for
  "Look a codec up by format.
   => (r/ok codec) | (r/err :error/unsupported-format {:format fmt})."
  [registry format]
  (if-let [codec (get registry format)]
    (r/ok codec)
    (r/err :error/unsupported-format {:format format})))
