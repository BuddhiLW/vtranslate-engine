(ns vtranslate.engine.adapters.translator.chunked
  "Decorator ITranslator that batches a large `texts` request into bounded
   concurrent chunks over an inner translator (order- + count-preserving).

   Splits into :chunk-size groups, translates them via hive-weave
   `bounded-pmap` (concurrency :concurrency), then concats in original order.
   A chunk that errs is retried on the optional :fallback translator; if it
   still errs (or no fallback) the whole batch fails LOUD with that err — no
   chunk is ever dropped or reordered (DIP: depends only on the port)."
  (:require [hive-dsl.result :as r]
            [hive-weave.parallel :as par]
            [vtranslate.engine.calc.batching :as batch]
            [vtranslate.engine.port.translator :as p.tr]))

(def ^:private default-chunk-size 50)
(def ^:private default-concurrency 4)

(defn- translate-chunk
  "Translate one `chunk` on `inner`; on err retry `fallback` (when non-nil).
   => (r/ok [translated ...]) | (r/err ...)."
  [inner fallback chunk source-language target-language opts]
  (let [res (p.tr/translate-batch inner chunk source-language target-language opts)]
    (if (or (r/ok? res) (nil? fallback))
      res
      (p.tr/translate-batch fallback chunk source-language target-language opts))))

(def ^:private chunk-timeout-error
  "bounded-pmap :fallback — a chunk that never returns (times out) surfaces as a
   loud translation error, never a silently dropped chunk."
  (r/err :error/translation-failed
         {:reason "chunk translation failed or timed out"}))

(defn- translate-chunks
  "Translate every chunk concurrently under a hard concurrency bound, chunk order
   preserved. => vector of per-chunk Results (one Result per chunk)."
  [{:keys [inner fallback concurrency]} chunks source-language target-language opts]
  (par/bounded-pmap
   {:concurrency concurrency :fallback chunk-timeout-error}
   (fn [chunk] (translate-chunk inner fallback chunk source-language target-language opts))
   chunks))

(defrecord ChunkedTranslator [inner chunk-size concurrency fallback]
  p.tr/ITranslator
  (translate-batch [this texts source-language target-language opts]
    (if (empty? texts)
      (r/ok [])
      (let [chunks (partition-all chunk-size texts)]
        (batch/reassemble
         (translate-chunks this chunks source-language target-language opts))))))

(defn make-chunked
  "Decorate `inner` ITranslator with bounded concurrent chunked batching.
   opts = {:chunk-size n (default 50) :concurrency c (default 4)
           :fallback inner-or-nil}."
  [inner {:keys [chunk-size concurrency fallback]
          :or   {chunk-size  default-chunk-size
                 concurrency default-concurrency}}]
  (->ChunkedTranslator inner chunk-size concurrency fallback))
