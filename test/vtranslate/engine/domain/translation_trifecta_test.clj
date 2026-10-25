(ns vtranslate.engine.domain.translation-trifecta-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [clojure.walk :as walk]
            [hive-dsl.result :as r]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.schema.domain :as s]
            [vtranslate.engine.shared :as shared]
            [vtranslate.engine.domain.ingestion :as ing]
            [vtranslate.engine.domain.job :as job]
            [vtranslate.engine.domain.transcription :as tr]
            [vtranslate.engine.domain.translation :as tl]
            [vtranslate.engine.domain.rendering :as render]))

(defn result-valid? [schema errors result]
  (if (r/ok? result)
    (m/validate schema (:ok result))
    (and (r/err? result) (contains? errors (:error result)))))
(defn plain [x] (walk/postwalk #(if (record? %) (into {} %) %) x))
(def text-gen (gen/fmap #(str "text-" %) gen/string-alphanumeric))
(def time-gen (gen/choose 0 1000000))
(def lang-gen (gen/elements (vec shared/supported-languages)))
(def invalid-times (gen/elements [nil -1 1.5 "0"]))
(def segment-data {:index 1 :start-ms 0 :end-ms 10 :text "hello" :confidence 0.5 :language "en"})
(def unit-data {:start-ms 0 :end-ms 10 :source-language "en" :source-text "hello" :target-text "ola"})
(def cue-data {:index 1 :start-ms 0 :end-ms 10 :lines ["hello"]})
(def segment (:ok (tr/make-segment segment-data)))
(def unit (:ok (tl/make-translation-unit unit-data)))
(def cue (:ok (render/make-cue cue-data)))
(def transcript (:ok (tr/make-transcript {:id "t" :asset-id "a" :language "en"})))
(def cues (:ok (tl/make-translated-cues {:id "u" :transcript-id "t" :source-language "en" :target-language "pt"})))
(def track (:ok (render/make-subtitle-track {:id "s" :source-id "u" :language "pt" :format :format/srt})))
(def asset (:ok (ing/make-media-asset {:id "a" :source-uri "movie.mp4" :kind :media/video})))
(def a-job (:ok (job/make-translation-job {:id "j" :asset-id "a" :target-language "pt"})))

(def unit-cases {:valid [unit-data] :bad-start [(assoc unit-data :start-ms -1)] :bad-end [(assoc unit-data :end-ms -1)] :inverted [(assoc unit-data :start-ms 20)] :language [(assoc unit-data :source-language "xx")] :blank [(assoc unit-data :target-text " ")] :optional [(assoc unit-data :source-language nil)] :annotation [(assoc unit-data :segment/omit? true)]})
(def unit-pred (partial result-valid? s/TranslationUnit #{:error/invalid-timecode :error/inverted-time-range :error/unsupported-language :error/translation-failed}))
(deftest unit-branches
  (doseq [[label args] unit-cases]
    (testing (str label) (is (unit-pred (apply tl/make-translation-unit args))))))
(deftrifecta unit tl/make-translation-unit
  {:apply? true
   :golden-path "test/golden/domain/translation-unit.edn"
   :xf plain
   :cases unit-cases
   :gen (gen/tuple (gen/fmap #(merge unit-data %) (gen/hash-map :start-ms (gen/choose 0 20) :target-text (gen/one-of [text-gen (gen/return " ")]) :source-language (gen/elements [nil "en" "xx"]))))
   :pred unit-pred
   :mutations [["missing-result" (fn [& _] nil)]]})


(def translated-cues-cases {:valid [{:source-language "en" :target-language "pt"}] :source [{:source-language "xx" :target-language "pt"}] :target [{:source-language "en" :target-language "xx"}] :same [{:source-language "en" :target-language "EN"}]})
(def translated-cues-pred (partial result-valid? s/TranslatedCues #{:error/unsupported-language :error/translation-failed}))
(deftest translated-cues-branches
  (doseq [[label args] translated-cues-cases]
    (testing (str label) (is (translated-cues-pred (apply tl/make-translated-cues args))))))
(deftrifecta translated-cues tl/make-translated-cues
  {:apply? true
   :golden-path "test/golden/domain/translation-translated-cues.edn"
   :xf plain
   :cases translated-cues-cases
   :gen (gen/tuple (gen/hash-map :id gen/int :transcript-id gen/int :source-language (gen/one-of [lang-gen (gen/return "xx")]) :target-language (gen/one-of [lang-gen (gen/return "xx")])))
   :pred translated-cues-pred
   :mutations [["missing-result" (fn [& _] nil)]]})


(def begin-cases {:valid [cues]})
(def begin-pred
  #(and (m/validate s/TranslatedCues %)
        (= :translation/translating (get-in % [:status :adt/variant]))
        (= (dissoc cues :status) (dissoc % :status))))
(deftest begin-branches
  (doseq [[label args] begin-cases]
    (testing (str label) (is (begin-pred (apply tl/begin args))))))
(deftrifecta begin tl/begin
  {:apply? true
   :golden-path "test/golden/domain/translation-begin.edn"
   :xf plain
   :cases begin-cases
   :gen (gen/tuple (gen/return cues))
   :pred begin-pred
   :mutations [["missing-result" (fn [& _] nil)]]})


(def add-unit-cases {:valid [cues unit]})
(def add-unit-pred
  #(and (m/validate s/TranslatedCues %)
        (= [unit] (:units %))
        (= (dissoc cues :units) (dissoc % :units))))
(deftest add-unit-branches
  (doseq [[label args] add-unit-cases]
    (testing (str label) (is (add-unit-pred (apply tl/add-unit args))))))
(deftrifecta add-unit tl/add-unit
  {:apply? true
   :golden-path "test/golden/domain/translation-add-unit.edn"
   :xf plain
   :cases add-unit-cases
   :gen (gen/tuple (gen/return cues) (gen/return unit))
   :pred add-unit-pred
   :mutations [["missing-result" (fn [& _] nil)]]})


(def complete-cases {:valid [(tl/add-unit cues unit)] :empty [cues] :complete [(assoc cues :status (tl/translation-status :translation/complete))] :failed [(assoc cues :status (tl/translation-status :translation/failed))]})
(def complete-pred
  (fn [result]
    (and (result-valid? s/TranslatedCues #{:error/translation-failed :error/illegal-transition} result)
         (if (r/ok? result)
           (and (= :translation/complete (get-in result [:ok :status :adt/variant]))
                (seq (get-in result [:ok :units])))
           (if (= :error/illegal-transition (:error result))
             (contains? #{:translation/complete :translation/failed} (:from result))
             (and (= "no units translated" (:reason result)) (= "u" (:segment-id result))))))))
(deftest complete-branches
  (doseq [[label args] complete-cases]
    (testing (str label) (is (complete-pred (apply tl/complete args))))))
(deftrifecta complete tl/complete
  {:apply? true
   :golden-path "test/golden/domain/translation-complete.edn"
   :xf plain
   :cases complete-cases
   :gen (gen/tuple (gen/fmap (fn [[status units]] (assoc cues :status (tl/translation-status status) :units units)) (gen/tuple (gen/elements [:translation/pending :translation/translating :translation/complete :translation/failed]) (gen/elements [[] [unit]]))))
   :pred complete-pred
   :mutations [["missing-result" (fn [& _] nil)]]})

