(ns vtranslate.engine.language-registry-test
  "The closed language registry: the campaign languages are admitted on both
   sides, and a tag compares case-insensitively (BCP-47) while the registry
   answers in its own spelling."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-dsl.result :as r]
            [vtranslate.engine.shared :as shared]
            [vtranslate.engine.schema :as s]))

(deftest ukrainian-and-the-campaign-languages-are-sources-and-targets
  (doseq [tag ["uk" "it" "ko" "tr" "pl" "nl" "hi" "id"]]
    (is (r/ok? (shared/make-source-language tag)) (str tag " is transcribable"))
    (is (r/ok? (shared/make-target-language tag)) (str tag " is renderable"))
    (is (some #{tag} s/language-tags) (str tag " is in the schema enum"))))

(deftest tags-compare-case-insensitively-and-come-back-canonical
  (is (= (r/ok "zh-hans") (shared/make-target-language "zh-Hans")))
  (is (= (r/ok "pt-BR") (shared/make-target-language "PT-br")))
  (is (= (r/ok "en") (shared/make-source-language "EN")))
  (is (= (r/ok "es-419") (shared/make-language "ES-419")))
  (testing "case never widens a side"
    (is (r/err? (shared/make-source-language "PT-BR"))
        "a regional variant is still no source, whatever its casing"))
  (testing "an unknown tag is still refused and reported as given"
    (is (= {:language "xx-YY" :side :target}
           (select-keys (shared/make-target-language "xx-YY") [:language :side])))
    (is (r/err? (shared/make-language nil)))))

(deftest canonical-tag-is-registry-membership-modulo-case
  (is (= "pt-BR" (shared/canonical-tag shared/target-languages "pt-br")))
  (is (nil? (shared/canonical-tag shared/source-languages "pt-br")))
  (is (nil? (shared/canonical-tag shared/target-languages 42))))
