(ns vtranslate.engine.providers.segmenter-registry-test
  (:require [clojure.test :refer [deftest is]]
            [hive-dsl.result :as r]
            [vtranslate.engine.providers.segmenter-registry :as reg]))

(deftest unknown-provider-fails-loud
  (let [res (reg/resolve-segmenter :nonsense {})]
    (is (r/err? res))
    (is (= :error/unknown-segmenter (:error res)))
    (is (vector? (:known res)) "reports the known provider set")))
