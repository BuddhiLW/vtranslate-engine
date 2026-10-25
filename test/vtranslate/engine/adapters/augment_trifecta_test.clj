(ns vtranslate.engine.adapters.augment-trifecta-test
  (:require [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.schema.port-values :as p]
            [vtranslate.engine.adapters.translator.augment :as a]
            [vtranslate.engine.port.translator :as tr]))
(defn observation [[texts opts extra :as in]]
  (let [seen (atom nil)
        delegate (reify tr/ITranslator (translate-batch [_ xs _ _ o] (reset! seen o) {:ok (vec xs)}))
        wrapped (a/wrap-opts delegate extra)]
    {:in in :out {:result (tr/translate-batch wrapped texts "en" "he" opts)
                  :opts @seen :same? (identical? delegate wrapped)}}))
(deftrifecta wrap-opts observation
  {:golden-path "test/golden/adapters-augment-wrap.edn"
   :cases {:empty [["a"] {:x 1} {}] :override [["a" "a"] {:x 1} {:x 2}]}
   :gen (gen/tuple (gen/vector gen/string-alphanumeric 0 8)
                   (gen/map gen/keyword gen/nat {:max-elements 5})
                   (gen/map gen/keyword gen/nat {:max-elements 5}))
   :pred (fn [{[texts opts extra] :in out :out}]
           (and (m/validate [:map [:result p/TranslateResult] [:opts :map] [:same? :boolean]] out)
                (= texts (get-in out [:result :ok])) (= (merge opts extra) (:opts out))
                (= (empty? extra) (:same? out))))})
