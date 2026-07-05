(ns vtranslate.engine.adapters.translator.identity
  "Always-available MT terminus — a passthrough translator. Returns the input
   texts UNCHANGED (count + order preserved): the defined degenerate ITranslator
   the router falls back to so the pipeline always has a translator (the drone
   :agentic-loop analogue). Powers subtitle-in -> subtitle-out smoke runs and is
   the safety net when no real MT provider is configured/available.

   Self-registers (defmethod resolve-translator :identity) — OCP, no core edit."
  (:require [hive-dsl.result :as r]
            [vtranslate.engine.port.translator :as p.tr]
            [vtranslate.engine.providers.translator-registry :as reg]))

(defrecord IdentityTranslator []
  p.tr/ITranslator
  (translate-batch [_ texts _source-language _target-language _opts]
    (r/ok (vec texts))))

(defn make-translator
  "Build the passthrough translator."
  []
  (->IdentityTranslator))

(defmethod reg/resolve-translator :identity
  [_ _config]
  (r/ok (make-translator)))
