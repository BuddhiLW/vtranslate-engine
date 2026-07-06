(ns vtranslate.engine.adapters.translator.contextual
  "Decorator ITranslator: translate in overlapping windows, each window sent to
   the inner translator with its neighbouring cues as context (opts
   :context/before and :context/after). Count- and order-preserving."
  (:require [hive-dsl.result :as r]
            [hive-weave.parallel :as par]
            [vtranslate.engine.calc.batching :as batch]
            [vtranslate.engine.port.translator :as p.tr]))

(def ^:private default-window 40)
(def ^:private default-lookaround 4)
(def ^:private default-concurrency 4)
(def ^:private default-timeout-ms 120000)

(def ^:private window-timeout-error
  (r/err :error/translation-failed
         {:reason "context-window translation failed or timed out"}))

(defn- translate-window
  "Translate one window's :texts on `inner`, passing :before/:after as
   opts :context/before and :context/after. => (r/ok [...]) | (r/err ...)."
  [inner source-language target-language opts {:keys [texts before after]}]
  (p.tr/translate-batch inner texts source-language target-language
                        (assoc opts :context/before before :context/after after)))

(defn- translate-windows
  "Translate every window concurrently under a hard bound + per-window timeout,
   window order preserved. => vector of per-window Results."
  [{:keys [inner concurrency timeout-ms]} windows source-language target-language opts]
  (par/bounded-pmap
   {:concurrency concurrency :timeout-ms timeout-ms :fallback window-timeout-error}
   (partial translate-window inner source-language target-language opts)
   windows))

(defrecord ContextualTranslator [inner window lookaround concurrency timeout-ms]
  p.tr/ITranslator
  (translate-batch [this texts source-language target-language opts]
    (if (empty? texts)
      (r/ok [])
      (let [windows (batch/context-windows texts window lookaround)]
        (batch/reassemble
         (translate-windows this windows source-language target-language opts))))))

(defn make-contextual
  "Build a ContextualTranslator over `inner`.
   opts = {:window n (40) :lookaround k (4) :concurrency c (4) :timeout-ms ms (120000)}."
  [inner {:keys [window lookaround concurrency timeout-ms]
          :or   {window      default-window
                 lookaround  default-lookaround
                 concurrency default-concurrency
                 timeout-ms  default-timeout-ms}}]
  (->ContextualTranslator inner window lookaround concurrency timeout-ms))

(defn wrap
  "Return `inner` wrapped with contextual windowing when config asks for it —
   [:translator-opts :context] a positive int, or [:translator-opts :window] set
   — else `inner` unchanged."
  [inner config]
  (let [{:keys [context window concurrency timeout-ms]} (get config :translator-opts)]
    (if (or (and (integer? context) (pos? context)) (integer? window))
      (make-contextual inner {:window      (or window default-window)
                              :lookaround  (or context default-lookaround)
                              :concurrency (or concurrency default-concurrency)
                              :timeout-ms  (or timeout-ms default-timeout-ms)})
      inner)))
