(ns vtranslate.engine.adapters.translator.augment
  "Generic decorator ITranslator: merge a fixed opts map into every translate-batch
   call. The engine threads OPAQUE extra opts (contributed by pipeline middleware)
   into translation without knowing their shape. Count/order-preserving — it only
   augments opts; the inner Result passes through untouched."
  (:require [vtranslate.engine.port.translator :as p.tr]))

(defrecord AugmentedTranslator [inner extra-opts]
  p.tr/ITranslator
  (translate-batch [_ texts source-language target-language opts]
    (p.tr/translate-batch inner texts source-language target-language
                          (merge opts extra-opts))))

(defn wrap-opts
  "Wrap `inner` so `extra-opts` is merged into every translate-batch opts.
   An empty/nil `extra-opts` returns `inner` unchanged."
  [inner extra-opts]
  (if (seq extra-opts)
    (->AugmentedTranslator inner extra-opts)
    inner))
