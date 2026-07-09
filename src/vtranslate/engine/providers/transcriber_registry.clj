(ns vtranslate.engine.providers.transcriber-registry
  "L3 — transcriber (ASR) provider registry. Open multimethod: each adapter ns
   self-registers (defmethod resolve-transcriber <provider-key> ...).
   resolve-transcriber => (r/ok <ITranscriber impl>) | (r/err ...); :default fails
   loud with the known provider set."
  (:require [vtranslate.engine.providers.registry :as registry]))

(defmulti resolve-transcriber
  "Build an ITranscriber for `provider-key`, reading opts from `config`."
  (fn [provider-key _config] provider-key))

(defn known
  "Registered transcriber provider keys, excluding the :default fallthrough."
  []
  (registry/known-keys resolve-transcriber))

(defmethod resolve-transcriber :default
  [provider-key _config]
  (registry/unknown-error
   :transcriber provider-key resolve-transcriber
   "set config [:providers :transcriber] (or VT_TRANSCRIBER) to a known key, or load an adapter ns that registers it"))
