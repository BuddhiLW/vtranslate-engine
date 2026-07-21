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

(defn index-groups
  "Partition `items` into groups keyed by `(key-fn item)`, tagging every item with
   its original 0-based position. => {group-key [[index item] ...] ...} with
   clojure.core/group-by semantics (groups by first key appearance, members in
   original order). The indices across all groups are a permutation of
   (range (count items)); every item appears in exactly one group."
  [items key-fn]
  (group-by (fn [[_ item]] (key-fn item))
            (map-indexed vector items)))

(defn group-payload
  "The ordered indices and `value-fn` payloads of one indexed group (a
   [[index item] ...] vector such as an `index-groups` value).
   => {:indices [index ...] :values [(value-fn item) ...]}, order preserved."
  [indexed-group value-fn]
  {:indices (mapv first indexed-group)
   :values  (mapv (comp value-fn second) indexed-group)})

(defn zip-indices
  "Zip `indices` with `values` positionally into [[index value] ...] iff their counts
   match; otherwise `(on-mismatch expected actual)`. The caller supplies `on-mismatch`
   (=> Result) so this algebra carries no domain error vocabulary.
   => (r/ok [[index value] ...]) | (on-mismatch (count indices) (count values))."
  [indices values on-mismatch]
  (if (= (count values) (count indices))
    (r/ok (mapv vector indices values))
    (on-mismatch (count indices) (count values))))

(defn scatter
  "Reassemble sparse `indexed` [[index value] ...] into a dense length-`n` vector in
   index order — the left inverse of the indexing done by `index-groups`. Every
   position 0 <= index < n must be filled exactly once (later duplicates overwrite
   earlier). => (r/ok [value ...]) when every slot is filled, else
   `(on-incomplete n filled-count)` for the caller's error vocabulary."
  [n indexed on-incomplete]
  (let [missing ::missing
        dense   (reduce (fn [acc [index value]] (assoc acc index value))
                        (vec (repeat n missing))
                        indexed)]
    (if (some #{missing} dense)
      (on-incomplete n (count (remove #{missing} dense)))
      (r/ok dense))))