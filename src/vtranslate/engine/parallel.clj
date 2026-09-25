(ns vtranslate.engine.parallel
  "Bounded fan-out and timed attempts whose deadline starts when the work
   starts, never when it was queued.

   `attempt` runs one thunk on its own daemon thread for at most `timeout-ms`
   and cancels it with an interrupt once that passes. `fan-out` applies a
   function to every item on at most `concurrency` threads and has no deadline
   of its own. `run-each` is a fan-out in which every item is one `attempt`, so
   an item waiting for a free thread spends none of its time.

   Every answer is a settled map, never a throw:
     {:status :ok          :value v}
     {:status :timed-out   :timeout-ms n}
     {:status :failed      :exception t}
     {:status :interrupted}
   :interrupted means the CALLING thread was interrupted: the work it waited on
   was cancelled and the caller's interrupt flag is set again."
  (:import (java.util.concurrent Callable CancellationException ExecutionException
                                 ExecutorService Executors Future FutureTask
                                 ThreadFactory TimeUnit TimeoutException)))

(defn- daemon-factory
  "A ThreadFactory of daemon threads named `prefix`-n."
  ^ThreadFactory [prefix]
  (let [n (atom 0)]
    (reify ThreadFactory
      (newThread [_ runnable]
        (doto (Thread. ^Runnable runnable (str prefix "-" (swap! n inc)))
          (.setDaemon true))))))

(defn- settle-future
  "Wait on `fut` for at most `timeout-ms` (nil: without bound) and settle it.
   A timeout, or an interrupt of the calling thread, cancels `fut` with an
   interrupt. => settled map (see ns doc)."
  [^Future fut timeout-ms]
  (try
    {:status :ok
     :value  (if timeout-ms
               (.get fut (long timeout-ms) TimeUnit/MILLISECONDS)
               (.get fut))}
    (catch TimeoutException _
      (.cancel fut true)
      {:status :timed-out :timeout-ms timeout-ms})
    (catch ExecutionException e
      {:status :failed :exception (or (.getCause e) e)})
    (catch CancellationException _
      {:status :interrupted})
    (catch InterruptedException _
      (.cancel fut true)
      (.interrupt (Thread/currentThread))
      {:status :interrupted})))

(defn attempt
  "Run thunk `f` on a new daemon thread for at most `timeout-ms` (a positive
   integer), counted from this call. On expiry that thread is interrupted and
   the attempt answers :timed-out at once, without waiting for `f` to notice.
   => {:status :ok :value v} | {:status :timed-out :timeout-ms n}
      | {:status :failed :exception t} | {:status :interrupted}"
  [timeout-ms f]
  {:pre [(pos-int? timeout-ms)]}
  (let [task (FutureTask. ^Callable f)]
    (doto (Thread. task "vtranslate-attempt")
      (.setDaemon true)
      (.start))
    (settle-future task timeout-ms)))

(defn fan-out
  "Apply `f` to every item of `items` on at most `concurrency` daemon threads.
   No deadline of its own: `f` bounds itself. When the calling thread is
   interrupted, every running item is interrupted and every unfinished one
   answers :interrupted.
   => [settled ...] in item order, each :value being (f item)."
  [concurrency f items]
  (let [items (vec items)]
    (if (empty? items)
      []
      (let [^ExecutorService pool
            (Executors/newFixedThreadPool
             (int (max 1 (min (long concurrency) (count items))))
             (daemon-factory "vtranslate-fan-out"))]
        (try
          (let [futures (mapv (fn [item] (.submit pool ^Callable (fn [] (f item))))
                              items)]
            (loop [pending futures
                   settled []]
              (if-let [fut (first pending)]
                (let [one (settle-future fut nil)]
                  (if (= :interrupted (:status one))
                    (into (conj settled one)
                          (repeat (count (rest pending)) {:status :interrupted}))
                    (recur (rest pending) (conj settled one))))
                settled)))
          (finally
            (.shutdownNow pool)))))))

(defn run-each
  "Apply `f` to every item of `items` on at most `concurrency` threads, each
   item one `attempt` of `timeout-ms` counted from when THAT item starts.
   => [settled ...] in item order, each :value being (f item)."
  [{:keys [concurrency timeout-ms]} f items]
  (mapv (fn [{:keys [status value] :as settled}]
          (if (= :ok status) value settled))
        (fan-out concurrency (fn [item] (attempt timeout-ms #(f item))) items)))
