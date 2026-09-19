(ns vtranslate.engine.adapters.translator.observed
  "Decorator ITranslator that reports every batch it translated: the texts it
   was given and what came back. The engine's callers show a job's work as it
   happens (phrases appearing as the translator returns them), so the report is
   made per provider-sized batch rather than once per language.

   Sits UNDER the chunked decorator, so with chunking on it reports each chunk
   as it lands, and without chunking it reports the whole group. The callback
   is `(:on-chunk-translated opts)`; with none, this is a pass-through.

   The report is `{:source-language :target-language :sources :translations
   :indices}`. `:indices` are the transcript positions of `sources` when the
   caller named them (`:segment-indices`, sliced at `:chunk-offset`), else nil.
   A failed batch reports nothing, and the callback can never fail a batch."
  (:require [hive-dsl.result :as r]
            [vtranslate.engine.port.translator :as p.tr]))

(defn- indices-of [{:keys [segment-indices chunk-offset]} n]
  (when (seq segment-indices)
    (let [from (min (long (or chunk-offset 0)) (count segment-indices))]
      (vec (take n (drop from segment-indices))))))

(defn- report! [on-chunk-translated report]
  (try
    (on-chunk-translated report)
    (catch Throwable _ nil)))

(defrecord ObservedTranslator [inner]
  p.tr/ITranslator
  (translate-batch [_ texts source-language target-language opts]
    (let [result (p.tr/translate-batch inner texts source-language target-language opts)]
      (when-let [on-chunk-translated (:on-chunk-translated opts)]
        (when (r/ok? result)
          (report! on-chunk-translated
                   {:source-language source-language
                    :target-language target-language
                    :sources         (vec texts)
                    :translations    (vec (:ok result))
                    :indices         (indices-of opts (count texts))})))
      result)))

(defn wrap
  "`inner`, reporting each translated batch to `(:on-chunk-translated opts)`."
  [inner]
  (->ObservedTranslator inner))
