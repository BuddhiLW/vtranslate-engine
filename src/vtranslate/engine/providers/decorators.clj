(ns vtranslate.engine.providers.decorators
  "Open registry of port decorators. Any number of addons may each contribute a
   decorator for a port (:translator, :transcriber, ...); `decorate` wraps a
   freshly resolved port with every contribution in ascending :order, so the
   lowest order sits innermost. Contributions are keyed by id, so an addon that
   registers again replaces its own entry instead of stacking a second one.

   A contribution is
     {:order    n                            ; default 100
      :wrap     (fn [port config] => port | (r/ok port) | (r/err ...))
      :identity (fn [config] => string|nil)} ; optional

   `:identity` names what the decorator does to the port's output under
   `config`, for caches keyed on that output (the transcript cache). nil means
   the decorator is inactive for that config."
  (:require [hive-dsl.result :as r]))

(defonce ^:private registry (atom {}))

(defn register!
  "Contribute decorator `id` to `port-key`. => id"
  [port-key id contribution]
  (swap! registry assoc-in [port-key id] (merge {:order 100} contribution))
  id)

(defn unregister!
  "Remove decorator `id` from `port-key`. => id"
  [port-key id]
  (swap! registry update port-key dissoc id)
  id)

(defn contributions
  "[[id contribution] ...] for `port-key`, innermost first (by :order, then id)."
  [port-key]
  (sort-by (fn [[id c]] [(:order c) (str id)]) (get @registry port-key)))

(defn- as-result [x]
  (if (and (map? x) (or (r/ok? x) (r/err? x))) x (r/ok x)))

(defn decorate
  "Wrap `port` with every decorator contributed to `port-key`.
   => (r/ok port) | the first (r/err ...) a decorator returns."
  [port-key port config]
  (reduce (fn [acc [_ {:keys [wrap]}]]
            (r/let-ok [p acc] (as-result (wrap p config))))
          (r/ok port)
          (contributions port-key)))

(defn identities
  "Sorted \"id=identity\" strings of the decorators active on `port-key` under
   `config`. => vector of strings (empty when none are active)."
  [port-key config]
  (->> (contributions port-key)
       (keep (fn [[id {f :identity}]]
               (when-let [v (and f (f config))] (str id "=" v))))
       sort
       vec))
