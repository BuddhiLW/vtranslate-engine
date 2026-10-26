(ns vtranslate.engine.adapters.identity-trifecta-test
  (:require [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.adapters.translator.identity :as i]
            [vtranslate.engine.port.translator :as port]
            [vtranslate.engine.schema.port-values :as p]))
(defn observation [texts] {:in texts :out (port/translate-batch (i/make-translator) texts "en" "he" {})})
(deftrifecta make-translator observation
  {:golden-path "test/golden/adapters-identity-make.edn"
   :cases {:empty [] :duplicate ["a" "a"] :unicode ["שלום" "世界"]}
   :gen (gen/vector gen/string-alphanumeric 0 20)
   :pred (fn [{:keys [in out]}] (and (m/validate p/TranslateResult out) (= in (:ok out))))})
