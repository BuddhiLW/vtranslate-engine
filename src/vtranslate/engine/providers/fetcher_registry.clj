(ns vtranslate.engine.providers.fetcher-registry
  (:require [vtranslate.engine.providers.registry :as registry]))

(defmulti resolve-fetcher
  "Build an ISourceFetcher for `provider-key`, reading opts from `config`."
  (fn [provider-key _config] provider-key))

(defn known
  "Registered fetcher provider keys, excluding the :default fallthrough."
  []
  (registry/known-keys resolve-fetcher))

(defmethod resolve-fetcher :default
  [provider-key _config]
  (registry/unknown-error
   :fetcher provider-key resolve-fetcher
   "set config [:providers :fetcher] to a known key, or load an addon that registers one (e.g. :vtranslate/fetch for :yt-dlp)"))
