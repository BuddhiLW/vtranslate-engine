(ns vtranslate.engine.schema.port-contract
  "Test-only contracts for port observations; no runtime instrumentation."
  (:require [vtranslate.engine.schema.port-values :as p]))

(def HookKeys [:set [:enum :asr/route-window :asr/clean]])
(def Raw [:vector :int])
(def RawResult (p/result-of Raw))
(def CoverageResult (p/result-of p/Spans))
(def NativeDescription [:map [:strategy :keyword]])
(def SourceResult (p/result-of :string))
