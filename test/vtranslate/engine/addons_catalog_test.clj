(ns vtranslate.engine.addons-catalog-test
  (:require [clojure.test :refer [deftest is testing]]
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

(deftest an-id-outside-the-catalog-is-refused-by-name
  ;; :acme/private-addon used to become the symbol acme.private-addon, which
  ;; is not the addon's namespace (acme.private-addon.addon): the require
  ;; failed as :addon/load-failed, naming neither the id nor the fix.
  (doseq [spec [:acme/private-addon {:addon :acme/private-addon}]]
    (let [normalized (sut/normalize-spec spec)
          loaded     (sut/load-addon! spec)]
      (is (nil? (:ns normalized)) (str (pr-str spec) ": no namespace is guessed"))
      (is (= :acme/private-addon (:unknown-id normalized)))
      (is (false? (:loaded? loaded)))
      (is (= :addon/unknown-id (:error loaded)) (pr-str spec))
      (is (re-find #":acme/private-addon" (:message loaded)) "the message names the id")
      (is (re-find #"\{:ns " (:message loaded)) "and says what to write instead")))
  (testing "a map that names its namespace is not an unknown id"
    (is (nil? (:unknown-id (sut/normalize-spec {:addon :acme/private-addon
                                                :ns 'acme.private-addon.addon})))))
  (testing "a bare keyword still names a namespace, as before"
    (is (= 'my-addon (:ns (sut/normalize-spec :my-addon))))))

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