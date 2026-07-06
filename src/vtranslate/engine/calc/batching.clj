(ns vtranslate.engine.calc.batching
  "Pure batching for the translator decorators: window a text batch, then fold
   the per-window Results back into one."
  (:require [hive-dsl.result :as r]))

(defn context-windows
  "Partition `texts` into contiguous windows of at most `size`, each with up to
   `lookaround` neighbouring strings on either side as context (clamped at the
   batch ends). Every text appears in exactly one window's :texts, in order.
   => [{:texts [s ...] :before [s ...] :after [s ...]} ...]."
  [texts size lookaround]
  (let [v    (vec texts)
        n    (count v)
        size (max 1 size)
        pad  (max 0 lookaround)]
    (mapv (fn [start]
            (let [end (min n (+ start size))]
              {:texts  (subvec v start end)
               :before (subvec v (max 0 (- start pad)) start)
               :after  (subvec v end (min n (+ end pad)))}))
          (range 0 n size))))

(defn reassemble
  "Fold per-window Results (in window order) into one Result: the first failing
   window wins, else the concatenation of all translations in original order.
   => (r/ok [translated ...]) | (r/err ...)."
  [window-results]
  (if-let [failure (first (remove r/ok? window-results))]
    failure
    (r/ok (into [] (mapcat :ok) window-results))))
