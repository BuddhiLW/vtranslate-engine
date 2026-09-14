(ns vtranslate.engine.providers.coverage-registry
  "OCP registry for ICoveragePolicy providers — the same provider-as-data shape
   the segmenter, transcriber and translator registries use: a policy ns
   self-registers its defmethod on require, and nothing here needs editing to
   add one."
  (:require [hive-dsl.result :as r]))

(defmulti resolve-coverage-policy
  "Build an ICoveragePolicy for `provider-key`, reading opts from `config`.
   => (r/ok ICoveragePolicy) | (r/err ...)."
  (fn [provider-key _config] provider-key))

(defmethod resolve-coverage-policy :default
  [provider-key _config]
  (r/err :error/unknown-coverage-policy
         {:provider-key provider-key
          :known        (vec (sort (remove #{:default} (keys (methods resolve-coverage-policy)))))
          :hint         "set config [:segmenter-opts :coverage] to a known key, or load an adapter ns that registers it"}))
