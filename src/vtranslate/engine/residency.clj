(ns vtranslate.engine.residency
  "The owner of one device's model budget. Before a model decodes, `ensure!`
   makes it resident on its IModelHost, unloading the least recently used
   models it must to fit the budget. The decision is whisper-budget/plan; this
   namespace collects the host's state, reconciles its ledger with it, and
   carries the plan out.

   policy: {:budget-mib        what the device may hold in total
            :model-mib         {model-id mib} measured costs
            :default-model-mib cost of a model :model-mib does not name}"
  (:require [hive-dsl.result :as r]
            [vtranslate.engine.adapters.transcriber.whisper-budget :as budget]
            [vtranslate.engine.port.model-host :as host]))

(def ^:private mib (* 1024 1024))

;; --- calculations -------------------------------------------------------------

(defn cost-bytes
  "Bytes `model-id` is expected to hold on the device under `policy`. => long"
  [{:keys [model-mib default-model-mib]} model-id]
  (* mib (long (or (get model-mib model-id) default-model-mib 0))))

(defn reconcile
  "`ledger` made to agree with the set of ids the host reports `loaded`. A model
   the host no longer holds is dropped. One it holds that the ledger never saw
   (loaded by another worker, or by a plain request) enters at its policy cost
   and FIRST in eviction order, since nothing here knows when it was last used.
   => {:budget-bytes n :resident {id bytes} :lru [id ...] :pinned #{}}"
  [policy {:keys [resident lru]} loaded]
  (let [known     (filterv (set loaded) lru)
        strangers (sort (remove (set known) loaded))]
    {:budget-bytes (* mib (long (or (:budget-mib policy) 0)))
     :resident     (into {} (map (fn [id] [id (get resident id (cost-bytes policy id))])) loaded)
     :lru          (vec (concat strangers known))
     :pinned       #{}}))

(defn refusal
  "The error for a plan that could not admit `model-id`. => Err"
  [state model-id want {:keys [shortfall reason]}]
  (r/err :error/model-host
         {:model      model-id
          :reason     reason
          :needed-mib (quot want mib)
          :short-mib  (quot (long (or shortfall 0)) mib)
          :budget-mib (quot (long (:budget-bytes state)) mib)
          :resident   (vec (keys (:resident state)))
          :busy       (vec (:pinned state))}))

;; --- boundary -----------------------------------------------------------------

(defn- evict!
  "Unload `victims` in order until one is busy. => Result<{:state :freed :busy}>,
   :busy naming the victim a decode still held, when one did."
  [h state victims]
  (reduce (fn [acc v]
            (r/let-ok [{:keys [state freed]} acc
                       outcome (host/unload! h v)]
              (if (= :busy outcome)
                (reduced (r/ok {:state (update state :pinned conj v) :freed freed :busy v}))
                (r/ok {:state (budget/forget state [v]) :freed (conj freed v)}))))
          (r/ok {:state state :freed [] :busy nil})
          victims))

(defn- settle!
  "Plan for `model-id` over `state`, evict, and replan while an eviction met a
   busy model; load `model-id` once the plan admits it without eviction.
   => Result<{:state :evicted}>"
  [h state model-id want evicted]
  (let [plan (budget/plan state model-id want)]
    (cond
      (not (:admit? plan))
      (refusal state model-id want plan)

      (:resident? plan)
      (r/ok {:state (update state :lru budget/touch model-id) :evicted evicted})

      (seq (:evict plan))
      (r/let-ok [{:keys [state freed]} (evict! h state (:evict plan))]
        (settle! h state model-id want (into evicted freed)))

      :else
      (r/let-ok [_ (host/load! h model-id)]
        (r/ok {:state (budget/admit state model-id want) :evicted evicted})))))

(defn owner
  "An owner of `host`'s device under `policy`. => owner map"
  [host policy]
  {:host host :policy policy :ledger (atom {:resident {} :lru []})})

(defonce ^:private owners (atom {}))

(defn shared-owner
  "The one owner of the device behind `host-key` under `policy`, made from
   `(make-host)` the first time and reused after, so its ledger outlives the
   transcriber that asked for it. => owner map"
  [host-key policy make-host]
  (let [k [host-key policy]]
    (or (get @owners k)
        (get (swap! owners #(if (contains? % k) % (assoc % k (owner (make-host) policy)))) k))))

(defn ensure!
  "Make `model-id` resident on the owner's host, unloading least recently used
   models first when the budget requires it. Serialised per owner.
   => (r/ok {:model id :evicted [id ...]}) | (r/err :error/model-host {...})"
  [{:keys [host policy ledger]} model-id]
  (locking ledger
    (r/let-ok [ids (host/loaded host)
               {:keys [state evicted]} (settle! host (reconcile policy @ledger ids)
                                                model-id (cost-bytes policy model-id) [])]
      (reset! ledger (select-keys state [:resident :lru]))
      (r/ok {:model model-id :evicted evicted}))))
