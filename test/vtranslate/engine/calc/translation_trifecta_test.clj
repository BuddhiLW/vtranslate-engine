(ns vtranslate.engine.calc.translation-trifecta-test
  (:require [clojure.string :as str]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.schema :as schema]
            [vtranslate.engine.schema.calc-text :as s]
            [vtranslate.engine.calc.transcription :as tx]
            [vtranslate.engine.calc.translation :as sut]
            [clojure.walk]))
(def language-gen (gen/elements ["en" "fr" "es" "pt-BR"]))
(def maybe-language-gen (gen/one-of [(gen/return nil) language-gen]))
(def transcript
  (:ok (tx/build-transcript {:id "t" :asset-id "a" :language "en"
                            :segments [{:start-ms 0 :end-ms 100 :text "hello" :confidence 1
                                        :segment/verbatim? true}]})))
(deftrifecta build-translated-cues sut/build-translated-cues
  {:golden-path "test/golden/calc-text-translation-build.edn" :apply? true
   :xf (fn [x] (clojure.walk/postwalk #(if (record? %) (into {} %) %) x))
   :cases {:valid [transcript ["bonjour"] {:id "tr" :target-language "fr"}]
           :mismatch [transcript [] {:id "tr" :target-language "fr"}]
           :same-language [transcript ["hello"] {:id "tr" :target-language "en"}]
           :blank [transcript [""] {:id "tr" :target-language "fr"}]
           :empty [(assoc transcript :segments []) [] {:id "tr" :target-language "fr"}]}
   :gen (gen/bind (gen/vector (gen/not-empty gen/string-alphanumeric) 1 10)
                  (fn [texts]
                    (gen/tuple
                     (gen/return (assoc transcript :segments (vec (repeat (count texts) (first (:segments transcript))))))
                     (gen/return texts)
                     (gen/fmap #(hash-map :id "tr" :target-language %) (gen/elements ["fr" "es" "pt-BR"])))))
   :pred #(and (m/validate (schema/result-of s/Translated) %) (:ok %)
               (= :translation/complete (get-in % [:ok :status :adt/variant]))
               (every? :segment/verbatim? (get-in % [:ok :units])))
   :mutations [["reject-all" (constantly {:error :error/translation-failed})]]})
(deftrifecta normalize-targets sut/normalize-targets
  {:golden-path "test/golden/calc-text-translation-targets.edn" :apply? true
   :cases {:missing [{}] :single [{:target-language " fr "}]
           :deduplicate [{:target-language "en" :target-languages [" fr " nil "" "fr" "en"]}]
           :fallback [{:target-language "en" :target-languages []}]}
   :gen (gen/tuple (gen/hash-map :target-language maybe-language-gen
                                 :target-languages (gen/vector (gen/one-of [maybe-language-gen (gen/elements [" " " fr " ""])]) 0 10)))
   :pred #(and (m/validate s/Strings %) (= % (vec (distinct %)))
               (every? (fn [x] (and (not (str/blank? x)) (= x (str/trim x)))) %))
   :mutations [["ignore-targets" (constantly [])]]})
(deftrifecta segment-source-language sut/segment-source-language
  {:golden-path "test/golden/calc-text-translation-source.edn" :apply? true
   :cases {:segment [{:language "en"} "fr" {:language "es"}]
           :fallback [{:language "en"} "fr" {}] :transcript [{:language "en"} nil {}]
           :und [{} nil {}]}
   :gen (gen/tuple (gen/hash-map :language maybe-language-gen) maybe-language-gen (gen/hash-map :language maybe-language-gen))
   :pred #(and (m/validate :string %) (contains? #{"en" "fr" "es" "pt-BR" "und"} %))
   :mutations [["always-und" (constantly "und")]]})
(deftrifecta translation-group sut/translation-group
  {:golden-path "test/golden/calc-text-translation-group.edn" :apply? true
   :cases {:verbatim [{} nil "fr" {:language "en" :segment/verbatim? true}]
           :omit [{} nil "fr" {:language "en" :segment/omit? true}]
           :same [{} nil "fr" {:language "fr"}]
           :translate [{} nil "fr" {:language "en"}] :unknown [{} nil "fr" {}]}
   :gen (gen/tuple (gen/hash-map :language maybe-language-gen) maybe-language-gen language-gen
                   (gen/hash-map :language maybe-language-gen :segment/verbatim? gen/boolean :segment/omit? gen/boolean))
   :pred #(m/validate [:or :string [:= sut/verbatim-group]] %)
   :mutations [["always-verbatim" (constantly sut/verbatim-group)]]})
(deftrifecta verbatim-translations sut/verbatim-translations
  {:golden-path "test/golden/calc-text-translation-verbatim.edn" :apply? true
   :cases {:empty [[]] :indexed [[[3 {:text "bonjour"}] [1 {:text "hello"}]]]}
   :gen (gen/tuple (gen/vector (gen/tuple gen/nat (gen/hash-map :text gen/string-alphanumeric)) 0 20))
   :pred #(and (m/validate (schema/result-of s/IndexedTexts) %) (:ok %))
   :mutations [["drop-texts" (constantly {:ok []})]]})
(deftrifecta translation-count-error sut/translation-count-error
  {:golden-path "test/golden/calc-text-translation-count.edn" :apply? true
   :cases {:missing ["batch" 2 1] :extra [nil 1 3]}
   :gen (gen/tuple gen/string-alphanumeric gen/nat gen/nat)
   :pred #(and (m/validate [:map [:error [:= :error/translation-failed]] [:segment-id :string] [:reason :string]] %)
               (re-matches #"translation count \d+ != segment count \d+" (:reason %)))
   :mutations [["success" (constantly {:ok nil})]]})
(deftrifecta target-outcomes sut/target-outcomes
  {:golden-path "test/golden/calc-text-translation-outcomes.edn" :apply? true
   :cases {:empty [[] [] ::unfinished]
           :mixed [["fr" "es" "de"] [{:ok {:id "tr"}} {:error :error/translation-failed :reason "bad"} ::unfinished] ::unfinished]}
   :gen (gen/bind (gen/vector language-gen 0 20)
                  (fn [targets] (gen/tuple (gen/return targets)
                                            (gen/vector (gen/one-of [(gen/fmap #(hash-map :ok %) gen/string-alphanumeric)
                                                                    (gen/return {:error :error/translation-failed})
                                                                    (gen/return ::unfinished)]) (count targets))
                                            (gen/return ::unfinished))))
   :pred #(m/validate s/Outcomes %)
   :mutations [["drop-results" (constantly {:delivered [] :failed []})]]})
