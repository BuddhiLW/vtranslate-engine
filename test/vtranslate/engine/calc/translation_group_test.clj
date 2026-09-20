(ns vtranslate.engine.calc.translation-group-test
  "Pure: which translation batch a segment belongs to."
  (:require [clojure.test :refer [deftest is]]
            [vtranslate.engine.calc.translation :as sut]))

(def ^:private transcript {:language "und"})

(deftest a-segment-translates-from-its-own-language
  (is (= "de" (sut/translation-group transcript nil "pt-BR" {:text "Ich bin" :language "de"})))
  (is (= "en" (sut/translation-group transcript nil "pt-BR" {:text "All free" :language "en"}))))

(deftest marked-segments-and-target-language-speech-are-verbatim
  (is (= sut/verbatim-group
         (sut/translation-group transcript nil "pt-BR" {:text "(speaking foreign language)" :language "en"
                                                       :segment/verbatim? true})))
  (is (= "en" (sut/translation-group transcript nil "pt-BR" {:text "(speaking foreign language)" :language "en"}))
      "the engine names no placeholder; only the mark makes a segment verbatim")
  (is (= sut/verbatim-group
         (sut/translation-group transcript nil "pt" {:text "Eu sou" :language "pt"})))
  (is (= "pt" (sut/translation-group transcript nil "pt-BR" {:text "Eu sou" :language "pt"}))
      "a regional target still translates from the base language"))
(deftest an-omitted-segment-is-never-sent-to-a-translator
  (is (= sut/verbatim-group
         (sut/translation-group transcript nil "pt-BR" {:text "Субтитры создавал DimaTorzok" :language "ru"
                                                       :segment/omit? true}))))


(deftest verbatim-translations-carry-each-text
  (is (= {:ok [[2 "[Music]"] [5 "hi"]]}
         (select-keys (sut/verbatim-translations [[2 {:text "[Music]"}] [5 {:text "hi"}]]) [:ok]))))
