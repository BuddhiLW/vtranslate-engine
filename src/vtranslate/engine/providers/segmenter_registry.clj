(ns vtranslate.engine.providers.segmenter-registry
  (:require [vtranslate.engine.providers.registry :as registry]))

(defmulti resolve-segmenter
  "Build an ISegmenter for `provider-key`, reading opts from `config`."
  (fn [provider-key _config] provider-key))

(defn known
  "Registered segmenter provider keys, excluding the :default fallthrough."
  []
  (registry/known-keys resolve-segmenter))

(defmethod resolve-segmenter :default
  [provider-key _config]
  (registry/unknown-error
   :segmenter provider-key resolve-segmenter
   "set config [:providers :segmenter] (or VT_SEGMENTER) to a known key, or load an adapter ns that registers it"))
