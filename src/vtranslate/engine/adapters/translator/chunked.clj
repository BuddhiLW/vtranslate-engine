(ns vtranslate.engine.adapters.translator.chunked
  "Decorator ITranslator that batches a large `texts` request into bounded
   concurrent chunks over an inner translator (order- + count-preserving).

   Splits into :chunk-size groups, translates them on at most :concurrency
   threads, then concats in original order. A chunk is translated in timed
   ATTEMPTS: each attempt runs under its own :timeout-ms, counted from when
   that attempt starts, and an attempt that overruns is cancelled with an
   interrupt. A chunk whose attempt times out, is interrupted or errs is tried
   again, up to :attempts in all, then the same way on the optional :fallback
   translator. A chunk no attempt translated fails the whole batch LOUD, its
   :reason naming the chunk and every attempt. No chunk is ever dropped or
   reordered (DIP: depends only on the port).

   The pool the chunks share has no deadline of its own: a chunk is bounded by
   its own attempts, so a chunk queued behind slow ones loses no time to them."
  (:require [clojure.string :as str]
            [hive-dsl.result :as r]
            [vtranslate.engine.calc.batching :as batch]
            [vtranslate.engine.parallel :as par]
            [vtranslate.engine.port.translator :as p.tr]
            [vtranslate.engine.providers.translator-registry :as reg]
            [vtranslate.engine.adapters.translator.observed :as observed]))

(def ^:private default-chunk-size 50)
(def ^:private default-concurrency 4)

(def ^:private default-attempts
  "Attempts each translator gets at one chunk, the first included."
  2)

(def ^:private default-timeout-ms
  "Bound on ONE attempt at one chunk, counted from when that attempt starts."
  120000)

(defn- live-reports
  "`opts` whose :on-chunk-translated falls silent once `given-up` is set, so an
   attempt that outlives its deadline never reports a chunk a later attempt
   reports again."
  [opts given-up]
  (if-let [report (:on-chunk-translated opts)]
    (assoc opts :on-chunk-translated (fn [chunk] (when-not @given-up (report chunk))))
    opts))

(defn- attempt-outcome
  "What attempt number `n`, settled as `settled` (vtranslate.engine.parallel),
   came to. => {:result ok-Result} | {:failure {...}}, plus :stop? true when
   the calling thread was interrupted."
  [n {:keys [status value exception timeout-ms]}]
  (case status
    :ok          (if (r/ok? value)
                   {:result value}
                   {:failure {:attempt n :outcome :error :cause value}})
    :timed-out   {:failure {:attempt n :outcome :timed-out :timeout-ms timeout-ms}}
    :failed      {:failure {:attempt n :outcome :threw
                            :class (.getName (class exception))
                            :message (.getMessage ^Throwable exception)}}
    :interrupted {:failure {:attempt n :outcome :interrupted} :stop? true}))

(defn- try-translator
  "Up to `attempts` timed attempts of `translator` at one chunk, stopping at
   the first success or when the calling thread is interrupted. `role` tags
   each failure (:inner | :fallback).
   => {:result ok-Result :failures [...]} | {:failures [...] :stopped? boolean}"
  [translator role {:keys [attempts timeout-ms]} texts source-language target-language opts]
  (loop [n 1
         failures []]
    (let [given-up (atom false)
          settled  (par/attempt timeout-ms
                                #(p.tr/translate-batch translator texts source-language
                                                       target-language
                                                       (live-reports opts given-up)))
          _        (when (#{:timed-out :interrupted} (:status settled))
                     (reset! given-up true))
          {:keys [result failure stop?]} (attempt-outcome n settled)
          failures (cond-> failures failure (conj (assoc failure :translator role)))]
      (cond
        result         {:result result :failures failures}
        stop?          {:failures failures :stopped? true}
        (< n attempts) (recur (inc n) failures)
        :else          {:failures failures}))))

(defn- describe-failure
  "One failed attempt as a phrase for the chunk's :reason."
  [{:keys [translator attempt outcome timeout-ms cause class message]}]
  (str (when (= :fallback translator) "fallback ")
       (when attempt (str "attempt " attempt " "))
       (case outcome
         :timed-out   (str "timed out after " timeout-ms " ms")
         :interrupted "was interrupted"
         :threw       (str "threw " class (when message (str ": " message)))
         :error       (str "failed (" (:error cause) ")"
                           (when-let [why (or (:reason cause) (:message cause))]
                             (str ": " why))))))

(defn- chunk-failure
  "The loud err for the chunk `info` ({:index :chunk-count :offset :size}) that
   no attempt translated, `failures` in attempt order. The last provider
   error's :class, :message and :status ride along at the top level.
   => (r/err :error/translation-failed {:reason s :attempts [...] ...})"
  [{:keys [index chunk-count offset size]} failures]
  (let [last-err (some :cause (rseq (vec failures)))
        tries    (count failures)]
    (r/err :error/translation-failed
           (merge (select-keys last-err [:class :message :status])
                  {:reason      (str "chunk " (inc index) " of " chunk-count
                                     " (texts " offset "-" (+ offset size -1) ")"
                                     " not translated after " tries
                                     " attempt" (when (not= 1 tries) "s") ": "
                                     (str/join "; " (map describe-failure failures)))
                   :chunk-index index
                   :chunk-count chunk-count
                   :attempts    (mapv #(dissoc % :cause) failures)}
                  (when last-err {:cause last-err})))))

(defn- translate-chunk
  "Translate one chunk (`info` carries its :texts and :offset) on the inner
   translator, then on the fallback when there is one.
   => (r/ok [translated ...]) | the chunk's loud err."
  [{:keys [inner fallback] :as settings} {:keys [texts offset] :as info}
   source-language target-language opts]
  (let [opts    (assoc opts :chunk-offset offset)
        primary (try-translator inner :inner settings texts
                                source-language target-language opts)]
    (cond
      (:result primary)
      (:result primary)

      (or (:stopped? primary) (nil? fallback))
      (chunk-failure info (:failures primary))

      :else
      (let [secondary (try-translator fallback :fallback settings texts
                                      source-language target-language opts)]
        (or (:result secondary)
            (chunk-failure info (into (:failures primary) (:failures secondary))))))))

(defn- chunk-infos
  "Each of `chunks` with its place in the whole request, `base` being where
   this request starts in its caller's.
   => [{:index :chunk-count :offset :size :texts} ...]"
  [chunks base]
  (let [n (count chunks)]
    (mapv (fn [index offset chunk]
            {:index index :chunk-count n :offset (+ base offset)
             :size (count chunk) :texts chunk})
          (range)
          (reductions + 0 (map count chunks))
          chunks)))

(defn- settled-chunk
  "The Result of the chunk `info` as the pool settled it."
  [info {:keys [status value exception]}]
  (case status
    :ok     value
    :failed (chunk-failure info [{:outcome :threw
                                  :class (.getName (class exception))
                                  :message (.getMessage ^Throwable exception)}])
    (chunk-failure info [{:outcome :interrupted}])))

(defn- translate-chunks
  "Translate every chunk on at most :concurrency threads, chunk order
   preserved. Each chunk is told where it starts in the whole request as
   `:chunk-offset`, so a decorator below can name its texts' positions.
   => vector of per-chunk Results (one Result per chunk)."
  [{:keys [concurrency] :as settings} chunks source-language target-language opts]
  (let [infos (chunk-infos chunks (long (or (:chunk-offset opts) 0)))]
    (mapv settled-chunk
          infos
          (par/fan-out concurrency
                       #(translate-chunk settings % source-language target-language opts)
                       infos))))

(defrecord ChunkedTranslator [inner chunk-size concurrency fallback timeout-ms attempts]
  p.tr/ITranslator
  (translate-batch [this texts source-language target-language opts]
    (if (empty? texts)
      (r/ok [])
      (batch/reassemble
       (translate-chunks this (partition-all chunk-size texts)
                         source-language target-language opts)))))

(defn make-chunked
  "Decorate `inner` ITranslator with bounded concurrent chunked batching.
   opts = {:chunk-size n (default 50) :concurrency c (default 4)
           :timeout-ms ms (default 120000, per ATTEMPT, counted from its start)
           :attempts n (default 2, per translator, per chunk)
           :fallback inner-or-nil}."
  [inner {:keys [chunk-size concurrency fallback timeout-ms attempts]
          :or   {chunk-size  default-chunk-size
                 concurrency default-concurrency
                 timeout-ms  default-timeout-ms
                 attempts    default-attempts}}]
  (->ChunkedTranslator inner chunk-size concurrency fallback timeout-ms attempts))

(defn- resolve-fallback
  "Build the fallback ITranslator named by provider key `k` via the registry,
   reading opts from `config`; nil `k` => no fallback.
   => (r/ok impl-or-nil) | (r/err ...) when the key is unknown/misconfigured."
  [k config]
  (if k
    (reg/resolve-translator k config)
    (r/ok nil)))

(defn- positive-int
  "`n` when it is a positive integer, else `default`."
  [n default]
  (if (pos-int? n) n default))

(defn wrap
  "Return `inner` wrapped with chunked batching when [:translator-opts :chunk-size]
   is a positive int, else `inner` unchanged. Each chunk is reported as it lands
   to the call's `:on-chunk-translated` (adapters.translator.observed).
   [:translator-opts :timeout-ms] bounds ONE attempt at a chunk, counted from
   when that attempt starts; [:translator-opts :chunk-attempts] is how many
   attempts each translator gets at a chunk (default 2);
   [:translator-opts :fallback-translator] names a provider key, resolved via
   the translator registry, tried once the inner translator's attempts are spent.
   => (r/ok translator) | (r/err ...) when the fallback provider can't be built."
  [inner config]
  (let [{:keys [chunk-size concurrency timeout-ms chunk-attempts fallback-translator]}
        (get config :translator-opts)]
    (if (and (integer? chunk-size) (pos? chunk-size))
      (r/let-ok [fallback (resolve-fallback fallback-translator config)]
        (r/ok (make-chunked (observed/wrap inner)
                            {:chunk-size  chunk-size
                             :concurrency (positive-int concurrency default-concurrency)
                             :timeout-ms  (positive-int timeout-ms default-timeout-ms)
                             :attempts    (positive-int chunk-attempts default-attempts)
                             :fallback    fallback})))
      (r/ok inner))))
