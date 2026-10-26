(ns vtranslate.engine.adapters.grid-trifecta-test
  (:require [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.adapters.segmenter.stub :as s]
            [vtranslate.engine.port.segmenter :as port]
            [vtranslate.engine.schema.port-values :as p]))
(def input-gen (gen/tuple (gen/choose 0 2000) (gen/choose 1 500)))
(defn tiling? [{[duration window] :in out :out}]
  (and (m/validate p/Spans out)
       (= duration (reduce + 0 (map #(- (:end-ms %) (:start-ms %)) out)))
       (every? #(<= 0 (:start-ms %) (:end-ms %) duration) out)
       (every? #(<= (- (:end-ms %) (:start-ms %)) window) out)))
(defn grid-observation [[duration window :as in]] {:in in :out (s/grid-spans duration window)})
(deftrifecta grid-spans grid-observation
  {:golden-path "test/golden/adapters-grid-spans.edn" :cases {:empty [0 10] :tail [21 10]}
   :gen input-gen :pred tiling?})
(defn make-observation [[duration window :as in]]
  {:in in :out (port/segment (s/make-segmenter window) {:duration-ms duration} {})})
(deftrifecta make-segmenter make-observation
  {:golden-path "test/golden/adapters-grid-make.edn" :cases {:empty [0 10] :tail [21 10]}
   :gen input-gen :pred (fn [{:keys [in out]}] (and (m/validate p/SegmentResult out) (tiling? {:in in :out (get-in out [:ok :spans])})))})
