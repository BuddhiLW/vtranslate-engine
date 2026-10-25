(ns vtranslate.engine.adapters.gap-filled-trifecta-test
  (:require [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.adapters.segmenter.gap-filled :as g]
            [vtranslate.engine.adapters.coverage.policies :as c]
            [vtranslate.engine.port.segmenter :as port]
            [vtranslate.engine.schema.port-values :as p]))
(def inner (reify port/ISegmenter (segment [_ _ _] {:ok {:spans [{:start-ms 1 :end-ms 2}]}})))
(defn make-observation [duration]
  {:in duration :out (port/segment (g/make-gap-filled inner (c/->PassThroughPolicy)) {:duration-ms duration} {})})
(deftrifecta make-gap-filled make-observation
  {:golden-path "test/golden/adapters-gap-make.edn" :cases {:zero 0 :long 100}
   :gen (gen/choose 0 1000)
   :pred (fn [{:keys [out]}] (and (m/validate p/SegmentResult out) (= [{:start-ms 1 :end-ms 2}] (get-in out [:ok :spans]))))})
(defn wrap-observation [duration]
  (let [wrapped (g/wrap inner {:segmenter-opts {:coverage :none}})]
    {:in duration :out (port/segment (:ok wrapped) {:duration-ms duration} {})}))
(deftrifecta wrap wrap-observation
  {:golden-path "test/golden/adapters-gap-wrap.edn" :cases {:zero 0 :long 100}
   :gen (gen/choose 0 1000)
   :pred (fn [{:keys [out]}] (and (m/validate p/SegmentResult out) (= [{:start-ms 1 :end-ms 2}] (get-in out [:ok :spans]))))})
