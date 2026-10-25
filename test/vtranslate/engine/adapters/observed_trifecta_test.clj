(ns vtranslate.engine.adapters.observed-trifecta-test
  (:require [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.schema.port-values :as p]
            [vtranslate.engine.adapters.translator.observed :as o]
            [vtranslate.engine.port.translator :as tr]))
(defn observation [texts]
  (let [reports (atom [])
        delegate (reify tr/ITranslator (translate-batch [_ xs _ _ _] {:ok (vec xs)}))
        result (tr/translate-batch (o/wrap delegate) texts "en" "he"
                                  {:segment-indices (vec (range (count texts))) :on-chunk-translated #(swap! reports conj %)})]
    {:in texts :out {:result result :reports @reports}}))
(deftrifecta wrap observation
  {:golden-path "test/golden/adapters-observed-wrap.edn"
   :cases {:empty [] :duplicates ["a" "a"] :unicode ["世界"]}
   :gen (gen/vector gen/string-alphanumeric 0 10)
   :pred (fn [{:keys [in out]}]
           (and (m/validate [:map [:result p/TranslateResult] [:reports [:vector :map]]] out)
                (= in (get-in out [:result :ok])) (= 1 (count (:reports out)))
                (= in (get-in out [:reports 0 :sources])) (= in (get-in out [:reports 0 :translations]))
                (= (when (seq in) (vec (range (count in)))) (get-in out [:reports 0 :indices]))))})
