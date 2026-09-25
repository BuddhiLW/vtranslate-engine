(ns vtranslate.engine.parallel-test
  "The engine's bounded fan-out: a deadline belongs to each item and starts
   when that item starts, an overrun is really cancelled, and an interrupted
   caller cancels what it was waiting on."
  (:require [clojure.test :refer [deftest is]]
            [vtranslate.engine.parallel :as par]))

(defn- sleeper
  "A thunk that sleeps `ms` and answers `value`, delivering true to the promise
   `interrupted` when its sleep is interrupted."
  [ms interrupted value]
  (fn []
    (try
      (Thread/sleep (long ms))
      value
      (catch InterruptedException _
        (deliver interrupted true)
        ::interrupted))))

(deftest an-attempt-answers-its-value
  (is (= {:status :ok :value 42} (par/attempt 1000 (fn [] 42)))))

(deftest an-overrunning-attempt-times-out-at-once-and-is-interrupted
  (let [interrupted (promise)
        started     (System/nanoTime)
        settled     (par/attempt 50 (sleeper 10000 interrupted :late))
        elapsed-ms  (/ (- (System/nanoTime) started) 1e6)]
    (is (= {:status :timed-out :timeout-ms 50} settled))
    (is (< elapsed-ms 2000) "the attempt did not wait for the thunk to notice")
    (is (true? (deref interrupted 2000 false)) "the thunk's thread was interrupted")))

(deftest a-throwing-attempt-settles-as-failed
  (let [{:keys [status exception]} (par/attempt 1000 #(throw (ex-info "boom" {})))]
    (is (= :failed status))
    (is (= "boom" (ex-message exception)))))

(deftest fan-out-keeps-item-order-and-its-concurrency-bound
  (let [running (atom 0)
        peak    (atom 0)
        out     (par/fan-out 2
                             (fn [n]
                               (swap! peak max (swap! running inc))
                               (Thread/sleep 30)
                               (swap! running dec)
                               (* n n))
                             (range 6))]
    (is (= (mapv (fn [n] {:status :ok :value (* n n)}) (range 6)) out))
    (is (<= @peak 2))))

(deftest fan-out-of-nothing-is-nothing
  (is (= [] (par/fan-out 4 identity []))))

(deftest run-each-counts-each-deadline-from-when-that-item-starts
  ;; One thread, four items each taking 60% of the bound: a deadline counted
  ;; from submit would cut the second item; one counted from its start cuts none.
  (is (= (mapv (fn [n] {:status :ok :value n}) (range 4))
         (par/run-each {:concurrency 1 :timeout-ms 300}
                       (fn [n] (Thread/sleep 180) n)
                       (range 4)))))

(deftest an-overrunning-item-costs-the-items-behind-it-nothing
  ;; The first item never returns. It is cut at its own bound, and the item
  ;; queued behind it still gets the whole of its own.
  (let [interrupted (promise)
        out         (par/run-each {:concurrency 1 :timeout-ms 300}
                                  (fn [n]
                                    (if (zero? n)
                                      ((sleeper 10000 interrupted :late))
                                      (do (Thread/sleep 180) n)))
                                  (range 3))]
    (is (= [{:status :timed-out :timeout-ms 300}
            {:status :ok :value 1}
            {:status :ok :value 2}]
           out))
    (is (true? (deref interrupted 2000 false)))))

(deftest an-interrupted-caller-cancels-what-it-waits-on
  (let [interrupted (promise)
        result      (promise)
        caller      (Thread. ^Runnable
                     (fn []
                       (deliver result
                                (par/fan-out 1 (fn [_] ((sleeper 10000 interrupted :late)))
                                             [:a :b]))))]
    (.start caller)
    (Thread/sleep 100)
    (.interrupt caller)
    (is (= [{:status :interrupted} {:status :interrupted}] (deref result 2000 ::hung)))
    (is (true? (deref interrupted 2000 false)) "the running item was interrupted")))
