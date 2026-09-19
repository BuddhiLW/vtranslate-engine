(ns vtranslate.engine.pipeline.extensions
  "OCP extension seam for the translation pipeline. Addons contribute middleware —
   fns (fn [resources ctx] => (r/ok ctx') | (r/err ...)) — at a named phase; the
   engine folds them over the pipeline context at that phase and never names what
   they do. With no addon loaded every phase resolves to no middleware, so the
   pipeline runs unchanged.

   Two ways in, and they compose: a `middleware` defmethod (one owner per phase),
   and `contribute!` (any number of addons per phase, each under its own id).
   `phase-middleware` is what the engine folds: the defmethod's list, then the
   contributions in ascending :order.")

(defmulti middleware
  "Registered middleware for `phase`, as an ordered seq of
   (fn [resources ctx] => Result<ctx>). Addons add one defmethod per phase they
   extend; the default is none."
  (fn [phase _resources] phase))

(defmethod middleware :default [_ _] [])

(defonce ^:private contributions (atom {}))

(defn contribute!
  "Contribute middleware `mw` to `phase` under `id`, at `order` (default 100).
   Registering the same id again replaces it. => id"
  ([phase id mw] (contribute! phase id mw 100))
  ([phase id mw order]
   (swap! contributions assoc-in [phase id] {:order order :mw mw})
   id))

(defn retract!
  "Remove contribution `id` from `phase`. => id"
  [phase id]
  (swap! contributions update phase dissoc id)
  id)

(defn contributed
  "The middleware contributed to `phase`, in ascending :order (then id)."
  [phase]
  (->> (get @contributions phase)
       (sort-by (fn [[id c]] [(:order c) (str id)]))
       (mapv (comp :mw val))))

(defn phase-middleware
  "Everything the engine folds at `phase`: the `middleware` defmethod's list,
   then the contributions."
  [phase resources]
  (into (vec (middleware phase resources)) (contributed phase)))
