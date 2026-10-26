(ns vtranslate.engine.domain.shared-trifecta-test
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

(def source-ref-cases {:valid ["movie"] :nil [nil] :blank ["  "] :not-string [42]})
(def source-ref-pred (partial result-valid? s/SourceRef #{:error/invalid-source}))
(deftest source-ref-branches
  (doseq [[label args] source-ref-cases]
    (testing (str label) (is (source-ref-pred (apply shared/make-source-ref args))))))
(deftrifecta source-ref shared/make-source-ref
  {:apply? true
   :golden-path "test/golden/domain/shared-source-ref.edn"
   :xf plain
   :cases source-ref-cases
   :gen (gen/tuple (gen/one-of [text-gen (gen/elements [nil "" " " 42])]))
   :pred source-ref-pred
   :mutations [["missing-result" (fn [& _] nil)]]})


(def make-language-cases {:valid ["en"] :case-insensitive ["EN"] :regional ["PT-br"] :unknown ["xx"] :nil [nil] :not-string [42]})
(def make-language-pred (partial result-valid? s/Language #{:error/unsupported-language}))
(deftest make-language-branches
  (doseq [[label args] make-language-cases]
    (testing (str label) (is (make-language-pred (apply shared/make-language args))))))
(deftrifecta make-language shared/make-language
  {:apply? true
   :golden-path "test/golden/domain/shared-make-language.edn"
   :xf plain
   :cases make-language-cases
   :gen (gen/tuple (gen/one-of [(gen/elements (vec shared/supported-languages)) (gen/elements ["EN" "PT-br" "xx" nil 42])]))
   :pred make-language-pred
   :mutations [["missing-result" (fn [& _] nil)]]})


(def make-source-language-cases {:valid ["en"] :case-insensitive ["EN"] :regional ["PT-br"] :unknown ["xx"] :nil [nil] :not-string [42]})
(def make-source-language-pred (partial result-valid? s/SourceLanguage #{:error/unsupported-language}))
(deftest make-source-language-branches
  (doseq [[label args] make-source-language-cases]
    (testing (str label) (is (make-source-language-pred (apply shared/make-source-language args))))))
(deftrifecta make-source-language shared/make-source-language
  {:apply? true
   :golden-path "test/golden/domain/shared-make-source-language.edn"
   :xf plain
   :cases make-source-language-cases
   :gen (gen/tuple (gen/one-of [(gen/elements (vec shared/source-languages)) (gen/elements ["EN" "PT-br" "xx" nil 42])]))
   :pred make-source-language-pred
   :mutations [["missing-result" (fn [& _] nil)]]})


(def make-target-language-cases {:valid ["en"] :case-insensitive ["EN"] :regional ["PT-br"] :unknown ["xx"] :nil [nil] :not-string [42]})
(def make-target-language-pred (partial result-valid? s/TargetLanguage #{:error/unsupported-language}))
(deftest make-target-language-branches
  (doseq [[label args] make-target-language-cases]
    (testing (str label) (is (make-target-language-pred (apply shared/make-target-language args))))))
(deftrifecta make-target-language shared/make-target-language
  {:apply? true
   :golden-path "test/golden/domain/shared-make-target-language.edn"
   :xf plain
   :cases make-target-language-cases
   :gen (gen/tuple (gen/one-of [(gen/elements (vec shared/target-languages)) (gen/elements ["EN" "PT-br" "xx" nil 42])]))
   :pred make-target-language-pred
   :mutations [["missing-result" (fn [& _] nil)]]})


(def timecode-cases {:zero [0] :valid [123] :negative [-1] :fraction [0.5] :nil [nil] :string ["0"]})
(def timecode-pred (partial result-valid? s/Timecode #{:error/invalid-timecode}))
(deftest timecode-branches
  (doseq [[label args] timecode-cases]
    (testing (str label) (is (timecode-pred (apply shared/make-timecode args))))))
(deftrifecta timecode shared/make-timecode
  {:apply? true
   :golden-path "test/golden/domain/shared-timecode.edn"
   :xf plain
   :cases timecode-cases
   :gen (gen/tuple (gen/one-of [time-gen invalid-times]))
   :pred timecode-pred
   :mutations [["missing-result" (fn [& _] nil)]]})


(def time-range-cases {:valid [1 2] :zero [0 0] :bad-start [-1 2] :bad-end [0 -1] :inverted [2 1]})
(def time-range-pred (partial result-valid? s/TimeRange #{:error/invalid-timecode :error/inverted-time-range}))
(deftest time-range-branches
  (doseq [[label args] time-range-cases]
    (testing (str label) (is (time-range-pred (apply shared/make-time-range args))))))
(deftrifecta time-range shared/make-time-range
  {:apply? true
   :golden-path "test/golden/domain/shared-time-range.edn"
   :xf plain
   :cases time-range-cases
   :gen (gen/tuple (gen/one-of [time-gen invalid-times]) (gen/one-of [time-gen invalid-times]))
   :pred time-range-pred
   :mutations [["missing-result" (fn [& _] nil)]]})


(def guard-transition-cases {:allowed [transcript :status #{:transcript/empty}] :refused [transcript :status #{}] :missing [transcript :absent #{:transcript/empty}]})
(def guard-transition-pred (partial result-valid? s/Transcript #{:error/illegal-transition}))
(deftest guard-transition-branches
  (doseq [[label args] guard-transition-cases]
    (testing (str label) (is (guard-transition-pred (apply shared/guard-transition args))))))
(deftrifecta guard-transition shared/guard-transition
  {:apply? true
   :golden-path "test/golden/domain/shared-guard-transition.edn"
   :xf plain
   :cases guard-transition-cases
   :gen (gen/tuple (gen/return transcript) (gen/elements [:status :absent]) (gen/elements [#{} #{:transcript/empty}]))
   :pred guard-transition-pred
   :mutations [["missing-result" (fn [& _] nil)]]})

