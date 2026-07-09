(ns vtranslate.engine.providers.composer-registry
  "L3 — video-composer (subtitle mux / burn-in) provider registry. Open
   multimethod: each adapter ns self-registers (defmethod resolve-composer
   <provider-key> ...). resolve-composer => (r/ok <IVideoComposer impl>) |
   (r/err ...); :default fails loud with the known provider set."
  (:require [vtranslate.engine.providers.registry :as registry]))

(defmulti resolve-composer
  "Build an IVideoComposer for `provider-key` (:soft | :hard), reading opts from `config`."
  (fn [provider-key _config] provider-key))

(defn known
  "Registered composer provider keys, excluding the :default fallthrough."
  []
  (registry/known-keys resolve-composer))

(defmethod resolve-composer :default
  [provider-key _config]
  (registry/unknown-error
   :composer provider-key resolve-composer
   "set config [:providers :composer] (or VT_COMPOSER) to :soft or :hard, or load an adapter ns that registers it"))
