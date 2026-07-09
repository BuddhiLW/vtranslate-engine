(ns vtranslate.engine.adapters.translator.identity-test
  "Property + registration tests for the IdentityTranslator passthrough terminus."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [hive-dsl.result :as r]
            [vtranslate.engine.port.translator :as p.tr]
            [vtranslate.engine.providers.translator-registry :as reg]
            [vtranslate.engine.adapters.translator.identity :as identity]))

(def gen-texts (gen/vector gen/string))

;; PROPERTY: passthrough — result is (r/ok (vec input)), unchanged, order + count preserved.
(defspec translate-batch-is-identity 300
  (prop/for-all [texts gen-texts]
    (let [res (p.tr/translate-batch (identity/make-translator) texts "en" "es" {})]
      (and (r/ok? res)
           (= (vec texts) (:ok res))
           (= (count texts) (count (:ok res)))
           (vector? (:ok res))))))

;; Empty input is the explicit degenerate case: ok empty vector.
(deftest empty-batch-returns-ok-empty
  (let [res (p.tr/translate-batch (identity/make-translator) [] "en" "es" {})]
    (is (r/ok? res))
    (is (= [] (:ok res)))))

;; Concrete order/count fixture — nothing is reordered, dropped, or mutated.
(deftest preserves-order-and-count
  (let [input ["gamma" "alpha" "beta" "alpha" ""]
        res   (p.tr/translate-batch (identity/make-translator) input "de" "fr" {})]
    (is (= (r/ok input) res) "same values in the same order, dupes + blanks kept")
    (is (= 5 (count (:ok res))))))

;; REGISTRY: :identity self-registers => (r/ok IdentityTranslator) that translates identically.
(deftest registry-resolves-identity
  (let [res (reg/resolve-translator :identity {})]
    (is (r/ok? res))
    (is (instance? vtranslate.engine.adapters.translator.identity.IdentityTranslator
                   (:ok res)))
    (is (= :identity (some #{:identity} (reg/known)))
        "provider key is in the known set")
    (let [input ["one" "two"]]
      (is (= (r/ok input)
             (p.tr/translate-batch (:ok res) input "en" "es" {}))
          "resolved translator is a passthrough"))))
