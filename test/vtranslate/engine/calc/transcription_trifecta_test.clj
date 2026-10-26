(ns vtranslate.engine.calc.transcription-trifecta-test
  (:require [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.schema :as schema]
            [vtranslate.engine.schema.calc-text :as s]
            [vtranslate.engine.calc.transcription :as sut]
            [clojure.walk]))
(deftrifecta detected-language sut/detected-language
  {:golden-path "test/golden/calc-text-transcription-language.edn" :apply? true
   :cases {:empty [[]] :unknown [[{:language "auto"} {:language "xyz"}]]
           :tie [[{:language "fr"} {:language "en"}]]
           :majority [[{:language "fr"} {:language "en"} {:language "en"}]]
           :canonical [[{:language "EN"}]]}
   :gen (gen/tuple (gen/vector (gen/hash-map :language (gen/elements [nil "auto" "und" "en" "fr" "pt-BR" "xyz"])) 0 30))
   :pred #(m/validate [:maybe schema/Language] %)
   :mutations [["no-detection" (constantly nil)]]})
(def segment {:start-ms 0 :end-ms 100 :text "Hello" :confidence 0.8})
(deftrifecta build-transcript sut/build-transcript
  {:golden-path "test/golden/calc-text-transcription-build.edn" :apply? true
   :xf (fn [x] (clojure.walk/postwalk #(if (record? %) (into {} %) %) x))
   :cases {:silent [{:id "t" :asset-id "a" :language "en" :segments []}]
           :detected [{:id "t" :asset-id "a" :language "und" :segments [(assoc segment :language "fr")]}]
           :speech [{:id "t" :asset-id "a" :language "en" :segments [segment]}]
           :blank [{:language "en" :segments [(assoc segment :text "")]}]
           :confidence [{:language "en" :segments [(assoc segment :confidence 2)]}]}
   :gen (gen/tuple (gen/hash-map :id gen/string-alphanumeric :asset-id gen/string-alphanumeric
                                 :language (gen/elements ["en" "fr" "und"])
                                 :segments (gen/vector (gen/fmap (fn [[start duration text]]
                                                                 (assoc segment :start-ms start :end-ms (+ start duration) :text text))
                                                               (gen/tuple gen/nat (gen/choose 1 10000) (gen/not-empty gen/string-alphanumeric))) 0 10)))
   :pred #(and (m/validate (schema/result-of s/Transcript) %) (:ok %)
               (= (range 1 (inc (count (:segments (:ok %))))) (map :index (:segments (:ok %)))))
   :mutations [["fail-speech" (constantly {:error :error/asr-failed})]]})
