(ns vtranslate.engine.providers.composer-registry
  "L3 — video-composer (subtitle mux / burn-in) provider registry. An OPEN
   multimethod factory: each adapter ns self-registers a (defmethod
   resolve-composer <provider-key> ...) so adding a provider never edits this file
   (OCP). resolve-composer => (r/ok <IVideoComposer impl>) | (r/err ...). The
   :default method fails LOUD with the known provider set — a composition strategy
   is chosen explicitly (:soft | :hard), never silently substituted."
  (:require [hive-dsl.result :as r]))

(defmulti resolve-composer
  "Build an IVideoComposer for `provider-key` (:soft | :hard), reading opts from
   `config`. Capability gating (JavaCV/native presence) belongs in each adapter's
   defmethod, returning (r/err :error/composer-unavailable ...) when its backend
   is absent."
  (fn [provider-key _config] provider-key))

(defn known
  "Registered composer provider keys, excluding the :default fallthrough."
  []
  (vec (remove #{:default} (keys (methods resolve-composer)))))

(defmethod resolve-composer :default
  [provider-key _config]
  (r/err :error/unknown-composer
         {:provider-key provider-key
          :known        (known)
          :hint         "set config [:providers :composer] (or VT_COMPOSER) to :soft or :hard, or load an adapter ns that registers it"}))
