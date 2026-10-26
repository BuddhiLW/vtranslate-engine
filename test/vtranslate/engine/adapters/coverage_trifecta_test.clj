(ns vtranslate.engine.adapters.coverage-trifecta-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.adapters.coverage.policies :as c]
            [vtranslate.engine.port.coverage :as port]
            [vtranslate.engine.schema.port-values :as p]))
(defn observation [[duration window :as in]]
  {:in in :out (port/cover (c/make-grid-fill {:window-ms window :min-gap-ms 1}) [] duration)})
(deftrifecta make-grid-fill observation
  {:golden-path "test/golden/adapters-coverage-make.edn"
   :cases {:empty [0 10] :tail [21 10]}
   :gen (gen/tuple (gen/choose 1 2000) (gen/choose 1 500))
   :pred (fn [{[duration window] :in out :out}]
           (and (m/validate (p/result-of p/Spans) out)
                (= duration (reduce + 0 (map #(- (:end-ms %) (:start-ms %)) (:ok out))))
                (every? #(<= (- (:end-ms %) (:start-ms %)) window) (:ok out))))})
(deftest offline-pass-through-policy
  (let [spans [{:start-ms 5 :end-ms 10}] out (port/cover (c/->PassThroughPolicy) spans 100)]
    (is (m/validate (p/result-of p/Spans) out)) (is (= spans (:ok out)))))
