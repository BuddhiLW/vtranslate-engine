(ns vtranslate.engine.domain.transcription-trifecta-test
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
(defn plain [x]
  ;; NaN is unequal to itself: normalize snapshots, not property inputs.
  (walk/postwalk #(cond
                    (and (double? %) (Double/isNaN %)) :ieee/nan
                    (record? %) (into {} %)
                    :else %) x))
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

(def confidence-cases {:zero [0] :one [1] :valid [0.5] :low [-0.1] :high [1.1] :nan [##NaN] :infinity [##Inf] :nil [nil] :string ["1"]})
(def confidence-pred (partial result-valid? s/Confidence #{:error/invalid-confidence}))
(deftest confidence-branches
  (doseq [[label args] confidence-cases]
    (testing (str label) (is (confidence-pred (apply tr/make-confidence args))))))
(deftrifecta confidence tr/make-confidence
  {:apply? true
   :golden-path "test/golden/domain/transcription-confidence.edn"
   :xf plain
   :cases confidence-cases
   :gen (gen/tuple (gen/one-of [(gen/double* {:infinite? false :NaN? false}) (gen/elements [0 1 nil "1" ##NaN ##Inf])]))
   :pred confidence-pred
   :mutations [["missing-result" (fn [& _] nil)]]})


(def segment-cases
  {:valid [segment-data]
   :bad-start [(assoc segment-data :start-ms -1)]
   :bad-end [(assoc segment-data :end-ms -1)]
   :inverted [(assoc segment-data :start-ms 20)]
   :confidence [(assoc segment-data :confidence 2)]
   :language [(assoc segment-data :language "xx")]
   :index [(assoc segment-data :index 0)]
   :text [(assoc segment-data :text " ")]
   :optional-language [(assoc segment-data :language nil)]
   :annotation [(assoc segment-data :segment/verbatim? true)]})
(def segment-pred (partial result-valid? s/Segment #{:error/invalid-timecode :error/inverted-time-range :error/invalid-confidence :error/unsupported-language :error/asr-failed}))
(deftest segment-branches
  (doseq [[label args] segment-cases]
    (testing (str label) (is (segment-pred (apply tr/make-segment args))))))
(deftrifecta segment tr/make-segment
  {:apply? true
   :golden-path "test/golden/domain/transcription-segment.edn"
   :xf plain
   :cases segment-cases
   :gen (gen/tuple (gen/fmap #(merge segment-data %) (gen/hash-map :index (gen/elements [0 1 2]) :text (gen/one-of [text-gen (gen/return " ")]) :confidence (gen/elements [0 0.5 1 2]) :start-ms (gen/choose 0 20) :language (gen/elements [nil "en" "xx"]))))
   :pred segment-pred
   :mutations [["missing-result" (fn [& _] nil)]]})


(def transcript-cases {:valid [{:language "en"}] :invalid [{:language "xx"}]})
(def transcript-pred (partial result-valid? s/Transcript #{:error/unsupported-language}))
(deftest transcript-branches
  (doseq [[label args] transcript-cases]
    (testing (str label) (is (transcript-pred (apply tr/make-transcript args))))))
(deftrifecta transcript tr/make-transcript
  {:apply? true
   :golden-path "test/golden/domain/transcription-transcript.edn"
   :xf plain
   :cases transcript-cases
   :gen (gen/tuple (gen/hash-map :id gen/int :asset-id gen/int :language (gen/one-of [lang-gen (gen/return "xx")])) )
   :pred transcript-pred
   :mutations [["missing-result" (fn [& _] nil)]]})


(def add-segment-cases {:valid [transcript segment]})
(def add-segment-pred
  #(and (m/validate s/Transcript %)
        (= :transcript/partial (get-in % [:status :adt/variant]))
        (= [segment] (:segments %))
        (= (dissoc transcript :status :segments) (dissoc % :status :segments))))
(deftest add-segment-branches
  (doseq [[label args] add-segment-cases]
    (testing (str label) (is (add-segment-pred (apply tr/add-segment args))))))
(deftrifecta add-segment tr/add-segment
  {:apply? true
   :golden-path "test/golden/domain/transcription-add-segment.edn"
   :xf plain
   :cases add-segment-cases
   :gen (gen/tuple (gen/return transcript) (gen/return segment))
   :pred add-segment-pred
   :mutations [["missing-result" (fn [& _] nil)]]})


(def complete-cases {:valid [(tr/add-segment transcript segment)] :empty [transcript] :complete [(assoc transcript :status (tr/transcript-status :transcript/complete))] :silent [(assoc transcript :status (tr/transcript-status :transcript/silent))] :failed [(assoc transcript :status (tr/transcript-status :transcript/failed))]})
(def complete-pred
  (fn [result]
    (and (result-valid? s/Transcript #{:error/asr-failed :error/illegal-transition} result)
         (if (r/ok? result)
           (and (= :transcript/complete (get-in result [:ok :status :adt/variant]))
                (seq (get-in result [:ok :segments])))
           (if (= :error/illegal-transition (:error result))
             (contains? #{:transcript/complete :transcript/silent :transcript/failed} (:from result))
             (= "no segments produced" (:reason result)))))))
(deftest complete-branches
  (doseq [[label args] complete-cases]
    (testing (str label) (is (complete-pred (apply tr/complete args))))))
(deftrifecta complete tr/complete
  {:apply? true
   :golden-path "test/golden/domain/transcription-complete.edn"
   :xf plain
   :cases complete-cases
   :gen (gen/tuple (gen/fmap (fn [[status segments]] (assoc transcript :status (tr/transcript-status status) :segments segments)) (gen/tuple (gen/elements [:transcript/empty :transcript/partial :transcript/complete :transcript/silent :transcript/failed]) (gen/elements [[] [segment]]))))
   :pred complete-pred
   :mutations [["missing-result" (fn [& _] nil)]]})


(def seal-silent-cases {:valid [transcript] :segments [(assoc transcript :segments [segment])] :partial [(tr/add-segment transcript segment)] :complete [(assoc transcript :status (tr/transcript-status :transcript/complete))] :silent [(:ok (tr/seal-silent transcript))] :failed [(assoc transcript :status (tr/transcript-status :transcript/failed))]})
(def seal-silent-pred
  (fn [result]
    (and (result-valid? s/Transcript #{:error/asr-failed :error/illegal-transition} result)
         (if (r/ok? result)
           (and (= :transcript/silent (get-in result [:ok :status :adt/variant]))
                (empty? (get-in result [:ok :segments])))
           (if (= :error/illegal-transition (:error result))
             (contains? #{:transcript/partial :transcript/complete :transcript/silent :transcript/failed} (:from result))
             (string? (:reason result)))))))
(deftest seal-silent-branches
  (doseq [[label args] seal-silent-cases]
    (testing (str label) (is (seal-silent-pred (apply tr/seal-silent args))))))
(deftrifecta seal-silent tr/seal-silent
  {:apply? true
   :golden-path "test/golden/domain/transcription-seal-silent.edn"
   :xf plain
   :cases seal-silent-cases
   :gen (gen/tuple (gen/fmap (fn [[status segments]] (assoc transcript :status (tr/transcript-status status) :segments segments)) (gen/tuple (gen/elements [:transcript/empty :transcript/partial :transcript/complete :transcript/silent :transcript/failed]) (gen/elements [[] [segment]]))))
   :pred seal-silent-pred
   :mutations [["missing-result" (fn [& _] nil)]]})

