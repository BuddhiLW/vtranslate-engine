(ns vtranslate.engine.providers.translator-registry
  "L3 — translator (MT) provider registry. Open multimethod: each adapter ns
   self-registers (defmethod resolve-translator <provider-key> ...).
   resolve-translator => (r/ok <ITranslator impl>) | (r/err ...); :default fails
   loud with the known provider set."
  (:require [vtranslate.engine.providers.registry :as registry]))

(defmulti resolve-translator
  "Build an ITranslator for `provider-key`, reading opts from `config`."
  (fn [provider-key _config] provider-key))

(defn known
  "Registered translator provider keys, excluding the :default fallthrough."
  []
  (registry/known-keys resolve-translator))

(defmethod resolve-translator :default
  [provider-key _config]
  (registry/unknown-error
   :translator provider-key resolve-translator
   "set config [:providers :translator] (or VT_TRANSLATOR) to a known key, or load an adapter ns that registers it"))
