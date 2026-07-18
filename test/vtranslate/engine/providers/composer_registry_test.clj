(ns vtranslate.engine.providers.composer-registry-test
  (:require [clojure.test :refer [deftest is]]
            [hive-dsl.result :as r]
            [vtranslate.engine.providers.composer-registry :as reg]))

(deftest unknown-provider-fails-loud
  (let [res (reg/resolve-composer :nonsense {})]
    (is (r/err? res))
    (is (= :error/unknown-composer (:error res)))
    (is (vector? (:known res)) "reports the known provider set")))

(deftest known-excludes-default
  (is (not (some #{:default} (reg/known))) ":default is never a public provider key"))
