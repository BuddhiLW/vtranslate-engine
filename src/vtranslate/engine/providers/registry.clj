(ns vtranslate.engine.providers.registry
  "Shared mechanism for the open provider-resolution multimethods."
  (:require [hive-dsl.result :as r]))

(defn known-keys
  "Registered dispatch keys of `multifn`, excluding the :default fallthrough."
  [multifn]
  (vec (remove #{:default} (keys (methods multifn)))))

(defn unknown-error
  "=> (r/err :error/unknown-<kind> {:provider-key :known :hint}) for an
   unregistered `provider-key` on `multifn`."
  [kind provider-key multifn hint]
  (r/err (keyword "error" (str "unknown-" (name kind)))
         {:provider-key provider-key
          :known        (known-keys multifn)
          :hint         hint}))
