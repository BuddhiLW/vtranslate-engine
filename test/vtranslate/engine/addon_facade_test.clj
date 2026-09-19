(ns vtranslate.engine.addon-facade-test
  (:require [clojure.test :refer [deftest is]]
            [hive-dsl.result :as r]
            [vtranslate.engine.addon :as sut]))

(deftest register-adapters-runs-register-with-the-addon-config
  (let [seen   (atom nil)
        facade (sut/facade {:id "x" :capabilities #{:x}
                            :register! (fn [c] (reset! seen c) (r/ok {:registered [:x]}))})]
    (is (= (r/ok {:registered [:x]}) ((:register-adapters! facade) {:addon/config {:a 1}})))
    (is (= {:a 1} @seen) "the loader's spec map is unwrapped to the addon's own config")))

(deftest addon-config-reads-every-spelling
  (is (= {:a 1} (sut/addon-config {:addon/config {:a 1}})))
  (is (= {:a 1} (sut/addon-config {:config {:a 1}})))
  (is (= {:a 1} (sut/addon-config {:a 1})))
  (is (= {} (sut/addon-config nil))))
