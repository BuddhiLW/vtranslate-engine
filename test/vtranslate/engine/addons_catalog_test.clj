(ns vtranslate.engine.addons-catalog-test
  (:require [clojure.test :refer [deftest is]]
            [vtranslate.engine.addons :as sut]))

(deftest keyword-addon-resolves-catalog-entry
  (is (= {:ns 'vtranslate.context.addon
          :classpath/aliases [:addon-context]
          :id :vtranslate/context
          :config {}}
         (sut/normalize-spec :vtranslate/context))))

(deftest map-addon-resolves-catalog-entry-and-config
  (is (= {:addon :vtranslate/context
          :ns 'vtranslate.context.addon
          :classpath/aliases [:addon-context]
          :id :vtranslate/context
          :config {:collection-id "movies"}}
         (sut/normalize-spec {:addon :vtranslate/context
                              :config {:collection-id "movies"}}))))

(deftest classpath-aliases-dedupes-catalog-presets
  (is (= [:addon-context]
         (sut/classpath-aliases [:vtranslate/context
                                 {:addon :vtranslate/context}]))))

(deftest iaddon-result-is-initialized-when-protocol-present
  (let [config {:collection-id "movies"}
        loaded (sut/load-addon! {:ns 'vtranslate.engine.addons.fake-iaddon
                                 :config config})
        addon (:addon/instance loaded)]
    (is (:loaded? loaded))
    (is addon)
    (is (= {:success? true
            :metadata {:config config}}
           (:result loaded)))
    (is (= {:created-with config
            :initialized-with config}
           @(:state addon)))))