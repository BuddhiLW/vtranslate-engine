(ns vtranslate.engine.providers.translator-registry
  "L3 — translator (MT) provider registry. An OPEN multimethod factory: each
   adapter ns self-registers a (defmethod resolve-translator <provider-key> ...)
   so adding a provider never edits this file (OCP). Cacheless/pure — a config
   swap is honored on the next resolve, no cache to bust.

   resolve-translator => (r/ok <ITranslator impl>) | (r/err ...). The :default
   method fails LOUD with the known provider set, never a silent fallback. Unlike
   ASR, MT HAS a legitimate always-available terminus (:identity passthrough)."
  (:require [hive-dsl.result :as r]))

(defmulti resolve-translator
  "Build an ITranslator for `provider-key`, reading any needed opts from `config`.
   Capability gating (api-key presence) belongs in each adapter's defmethod,
   returning (r/err :error/translator-unavailable ...) when its backend is absent."
  (fn [provider-key _config] provider-key))

(defn known
  "Registered provider keys, excluding the :default fallthrough."
  []
  (vec (remove #{:default} (keys (methods resolve-translator)))))

(defmethod resolve-translator :default
  [provider-key _config]
  (r/err :error/unknown-translator
         {:provider-key provider-key
          :known        (known)
          :hint         "set config [:providers :translator] (or VT_TRANSLATOR) to a known key, or load an adapter ns that registers it"}))
