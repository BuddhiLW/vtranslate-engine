(ns vtranslate.engine.providers.native-call-registry
  "OCP registry for INativeCall strategies: the same provider-as-data shape the
   segmenter, transcriber, translator and coverage registries use. A strategy ns
   self-registers its defmethod on require, and nothing here is edited to add
   one."
  (:require [hive-dsl.result :as r]))

(defmulti resolve-native-call
  "Build an INativeCall for `strategy-key`, reading opts from `config`.
   => (r/ok INativeCall) | (r/err ...)."
  (fn [strategy-key _config] strategy-key))

(defmethod resolve-native-call :default
  [strategy-key _config]
  (r/err :error/unknown-native-call-strategy
         {:strategy-key strategy-key
          :known (vec (sort (remove #{:default} (keys (methods resolve-native-call)))))
          :hint  (str "set config [:native-call :strategy] to a known key, or "
                      "load an adapter ns that registers it")}))
