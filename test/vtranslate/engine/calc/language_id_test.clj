(ns vtranslate.engine.calc.language-id-test
  "Pure: text -> the source-registry language it is written in."
  (:require [clojure.test :refer [deftest is are testing]]
            [vtranslate.engine.calc.language-id :as sut]
            [vtranslate.engine.shared :as shared]))

(deftest latin-script-languages-are-told-apart-by-function-words
  (are [tag text] (= tag (sut/identify text))
    "de" "einer der berühmtesten Reden seines Lebens."
    "de" "Das hat die Menschen zutiefst beeindruckt, denn es stand ja ganz klar"
    "en" "wherever they may live, our citizens of Berlin."
    "pt" "Eu sou um berlinense"
    "es" "Soy de Madrid y es muy bonito"
    "it" "Questo è molto bello, non è vero?"
    "nl" "Dit is een mooie dag en het was goed"
    "pl" "To jest bardzo dobre i nie wiem"
    "tr" "Bu çok güzel bir gün"
    "id" "Saya tidak tahu dan itu bukan"))

(deftest script-decides-non-latin-languages
  (are [tag text] (= tag (sut/identify text))
    "ru" "Я ухожу"
    "uk" "Я йду додому і їм"
    "ja" "こんにちは"
    "ko" "안녕하세요"
    "zh" "我是柏林人"
    "hi" "नमस्ते दुनिया"
    "he" "שלום"
    "ar" "مرحبا بالعالم"
    "fa" "من پدر هستم"))

(deftest a-quotation-does-not-decide-the-line
  (is (= "en" (sut/identify "I take pride in the words \"Ich bin ein Berliner.\""))
      "the quoted German is set aside; the line around it is English")
  (is (= "de" (sut/identify "\"Ich bin ein Berliner\""))
      "a line that is ONLY a quotation is judged by the quotation"))

(deftest no-evidence-is-no-answer
  (is (nil? (sut/identify "Berlin")) "a proper noun alone decides nothing")
  (is (nil? (sut/identify "")))
  (is (nil? (sut/identify nil))))

(deftest a-short-english-phrase-is-not-read-as-portuguese
  (is (not= "pt" (sut/identify "As a free man"))))

(deftest every-answer-is-a-registry-source-language
  (doseq [text ["the and of" "der die das" "o que é" "Я" "안녕" "x y z"]]
    (when-let [tag (sut/identify text)]
      (is (contains? shared/source-languages tag) text))))
