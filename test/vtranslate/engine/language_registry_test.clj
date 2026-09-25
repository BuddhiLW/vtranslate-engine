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

(def ^:private rendered-only
  "Languages a translator renders into that no transcriber is asked to hear."
  ["ca" "cs" "da" "el" "fi" "hu" "ms" "nb" "ro" "sv" "vi" "bg" "sk" "hr" "bn" "ur"])

(deftest the-rendered-only-languages-are-targets-and-never-sources
  (doseq [tag rendered-only]
    (is (= (r/ok tag) (shared/make-target-language tag)) (str tag " is renderable"))
    (is (= (r/ok tag) (shared/make-target-language (clojure.string/upper-case tag)))
        (str tag " compares case-insensitively"))
    (is (= {:error :error/unsupported-language :language tag :side :source}
           (shared/make-source-language tag))
        (str tag " is not transcribable"))
    (is (r/ok? (shared/make-language tag)) (str tag " is in the union"))
    (is (some #{tag} s/language-tags) (str tag " is in the schema enum"))))

(deftest every-latin-script-target-has-function-words
  (doseq [tag ["ca" "cs" "da" "fi" "hu" "ms" "nb" "ro" "sv" "vi" "sk" "hr"]]
    (is (seq (get shared/function-words tag)) (str tag " has function words"))))

(deftest a-target-only-language-shares-a-source-word-only-when-english-does
  (let [english      (get shared/function-words "en")
        source-words (into #{}
                           (comp (filter (comp shared/source-languages key))
                                 (mapcat val))
                           shared/function-words)]
    (doseq [[tag words] shared/function-words
            :when (not (contains? shared/source-languages tag))]
      (is (contains? shared/target-languages tag) (str tag " is a registry target"))
      (is (empty? (remove english (filter source-words words)))
          (str tag " lists a word of a source language that English does not")))))

(deftest the-function-words-of-each-source-language-still-name-it
  (let [score (fn [tokens words] (count (filter words tokens)))]
    (doseq [[tag words] shared/function-words
            :when (contains? shared/source-languages tag)]
      (let [scores (into {} (map (fn [[t ws]] [t (score words ws)])) shared/function-words)
            best   (apply max (vals scores))]
        (is (= [tag] (keep (fn [[t s]] (when (= s best) t)) scores))
            (str tag "'s own words make " tag " the only best-scoring language"))))))

(deftest english-words-a-target-uses-as-its-own-are-not-leaks
  (doseq [[tag word] [["sv" "i"] ["da" "at"] ["nb" "her"] ["da" "for"] ["ca" "on"]
                      ["cs" "to"] ["sk" "to"] ["hr" "i"] ["fi" "on"] ["hu" "is"]]]
    (is (contains? (get shared/function-words "en") word))
    (is (contains? (get shared/function-words tag) word)
        (str "\"" word "\" is an ordinary " tag " word"))))
