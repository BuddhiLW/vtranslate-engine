(ns vtranslate.engine.adapters.chunked-trifecta-test
  (:require [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.schema.port-values :as p]
            [vtranslate.engine.adapters.translator.chunked :as c]
            [vtranslate.engine.port.translator :as tr]))
(def delegate (reify tr/ITranslator (translate-batch [_ xs _ _ _] {:ok (vec xs)})))
(defn make-observation [[texts size :as in]]
  {:in in :out (tr/translate-batch (c/make-chunked delegate {:chunk-size size :concurrency 2 :attempts 1}) texts "en" "he" {})})
(deftrifecta make-chunked make-observation
  {:golden-path "test/golden/adapters-chunked-make.edn"
   :cases {:empty [[] 2] :tail [["a" "a" "b"] 2]}
   :gen (gen/tuple (gen/vector gen/string-alphanumeric 0 15) (gen/choose 1 8))
   :pred (fn [{[texts] :in out :out}] (and (m/validate p/TranslateResult out) (= texts (:ok out))))})
(defn wrap-observation [[texts size :as in]]
  (let [result (c/wrap delegate {:translator-opts {:chunk-size size :concurrency 1 :chunk-attempts 1}})]
    {:in in :out {:same? (identical? delegate (:ok result))
                  :result (tr/translate-batch (:ok result) texts "en" "he" {})}}))
(deftrifecta wrap wrap-observation
  {:golden-path "test/golden/adapters-chunked-wrap.edn"
   :cases {:disabled [["a"] 0] :enabled [["a" "b" "a"] 2]}
   :gen (gen/tuple (gen/vector gen/string-alphanumeric 0 12) (gen/choose -1 8))
   :pred (fn [{[texts size] :in out :out}]
           (and (m/validate [:map [:same? :boolean] [:result p/TranslateResult]] out)
                (= texts (get-in out [:result :ok])) (= (not (pos? size)) (:same? out))))})
