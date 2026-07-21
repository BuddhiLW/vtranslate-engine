(ns vtranslate.engine.pipeline.fsm-test
  "Contract for the generic linear-stage pipeline runner (domain-free): stages thread
   an ok context through in declared order, the first :error short-circuits every
   later stage, and the Result <-> state seam (with-result / continue? / halted?)
   behaves. The two engine pipelines (api/run-job, api/run-subtitle-job) are the
   integration coverage; this pins the runner in isolation."
  (:require [clojure.test :refer [deftest is]]
            [hive-dsl.result :as r]
            [vtranslate.engine.pipeline.fsm :as pf]))

(defn- trail-step
  "A stage that appends `k` to the ctx :trail on an ok state."
  [k]
  (fn [_resources state]
    (pf/with-result state (fn [ctx] (r/ok (update ctx :trail (fnil conj []) k))))))

(defn- boom-step [_resources state]
  (pf/with-result state (fn [_ctx] (r/err :error/boom {:at :stage-2}))))

(defn- start-step [_resources spec]
  (pf/result-state (r/ok {:trail [] :spec spec})))

(def ^:private ok-fsm
  (pf/compile-stages
   [(pf/stage pf/start-id start-step)
    (pf/stage :s/a (trail-step :a))
    (pf/stage :s/b (trail-step :b))
    (pf/stage :s/c (trail-step :c))]))

(def ^:private boom-fsm
  (pf/compile-stages
   [(pf/stage pf/start-id start-step)
    (pf/stage :s/a (trail-step :a))
    (pf/stage :s/boom boom-step)
    (pf/stage :s/c (trail-step :c))]))

(deftest stages-thread-ctx-in-declared-order
  (let [res (pf/run-pipeline (pf/pipeline {} ok-fsm) {:job :j})]
    (is (r/ok? res))
    (is (= [:a :b :c] (:trail (:ok res))))
    (is (= {:job :j} (:spec (:ok res))) "the start stage receives the spec as its data arg")))

(deftest first-error-short-circuits-the-rest
  (let [res (pf/run-pipeline (pf/pipeline {} boom-fsm) {:job :j})]
    (is (r/err? res))
    (is (= :error/boom (:error res)))
    (is (= :stage-2 (:at res)) "the failing stage's error data propagates unchanged")))

(deftest with-result-skips-f-on-error-state
  (let [called (atom false)
        out    (pf/with-result (pf/result-state (r/err :error/prior {:k 1}))
                 (fn [_] (reset! called true) (r/ok :never)))]
    (is (= :error/prior (:error (pf/result-of out))))
    (is (false? @called) "f is not invoked when the incoming state already carries an error")))

(deftest with-result-runs-f-on-ok-state
  (let [out (pf/with-result (pf/result-state (r/ok {:n 1}))
              (fn [ctx] (r/ok (update ctx :n inc))))]
    (is (r/ok? (pf/result-of out)))
    (is (= 2 (:n (:ok (pf/result-of out)))))))

(deftest continue-and-halt-read-the-carried-result
  (is (true?  (pf/continue? (pf/result-state (r/ok :x)))))
  (is (false? (pf/continue? (pf/result-state (r/err :error/e {})))))
  (is (true?  (pf/halted?   (pf/result-state (r/err :error/e {})))))
  (is (false? (pf/halted?   (pf/result-state (r/ok :x))))))

(deftest stage-is-an-ijobstage-value-object
  (let [s (pf/stage :s/x (fn [res state] [:handled res state]))]
    (is (= :s/x (pf/stage-id s)))
    (is (= [:handled {:r 1} {:s 2}] (pf/apply-stage s {:r 1} {:s 2})))))
