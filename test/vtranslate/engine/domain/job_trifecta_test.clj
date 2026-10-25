(ns vtranslate.engine.domain.job-trifecta-test
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

(def translation-job-cases {:valid [{:target-language "en"}] :regional [{:target-language "PT-br"}] :invalid [{:target-language "xx"}]})
(def translation-job-pred (partial result-valid? s/TranslationJob #{:error/unsupported-language}))
(deftest translation-job-branches
  (doseq [[label args] translation-job-cases]
    (testing (str label) (is (translation-job-pred (apply job/make-translation-job args))))))
(deftrifecta translation-job job/make-translation-job
  {:apply? true
   :golden-path "test/golden/domain/job-translation-job.edn"
   :xf plain
   :cases translation-job-cases
   :gen (gen/tuple (gen/hash-map :id gen/int :asset-id gen/int :target-language (gen/one-of [lang-gen (gen/elements ["PT-br" "xx" nil])])) )
   :pred translation-job-pred
   :mutations [["missing-result" (fn [& _] nil)]]})


(def advance-cases {:valid [a-job] :completed [(assoc a-job :state (job/job-state :job/completed))] :failed [(assoc a-job :state (job/job-state :job/failed))]})
(def advance-pred (partial result-valid? s/TranslationJob #{:error/illegal-transition}))
(deftest advance-branches
  (doseq [[label args] advance-cases]
    (testing (str label) (is (advance-pred (apply job/advance args))))))
(deftrifecta advance job/advance
  {:apply? true
   :golden-path "test/golden/domain/job-advance.edn"
   :xf plain
   :cases advance-cases
   :gen (gen/tuple (gen/fmap #(assoc a-job :state (job/job-state %)) (gen/elements [:job/pending :job/ingesting :job/transcribing :job/translating :job/rendering :job/completed :job/failed])))
   :pred advance-pred
   :mutations [["missing-result" (fn [& _] nil)]]})


(def complete-cases {:valid [a-job] :completed [(assoc a-job :state (job/job-state :job/completed))] :failed [(assoc a-job :state (job/job-state :job/failed))]})
(def complete-pred
  (fn [result]
    (and (result-valid? s/TranslationJob #{:error/illegal-transition} result)
         (if (r/ok? result)
           (= :job/completed (get-in result [:ok :state :adt/variant]))
           (contains? #{:job/completed :job/failed} (:from result))))))
(deftest complete-branches
  (doseq [[label args] complete-cases]
    (testing (str label) (is (complete-pred (apply job/complete args))))))
(deftrifecta complete job/complete
  {:apply? true
   :golden-path "test/golden/domain/job-complete.edn"
   :xf plain
   :cases complete-cases
   :gen (gen/tuple (gen/fmap #(assoc a-job :state (job/job-state %)) (gen/elements [:job/pending :job/ingesting :job/transcribing :job/translating :job/rendering :job/completed :job/failed])))
   :pred complete-pred
   :mutations [["missing-result" (fn [& _] nil)]]})


(def fail-cases {:valid [a-job :error/asr-failed] :completed [(assoc a-job :state (job/job-state :job/completed)) :error/asr-failed] :failed [(assoc a-job :state (job/job-state :job/failed)) :error/asr-failed]})
(def fail-pred
  (fn [result]
    (and (result-valid? s/TranslationJob #{:error/illegal-transition} result)
         (if (r/ok? result)
           (and (= :job/failed (get-in result [:ok :state :adt/variant]))
                (= :error/asr-failed (get-in result [:ok :error])))
           (contains? #{:job/completed :job/failed} (:from result))))))
(deftest fail-branches
  (doseq [[label args] fail-cases]
    (testing (str label) (is (fail-pred (apply job/fail args))))))
(deftrifecta fail job/fail
  {:apply? true
   :golden-path "test/golden/domain/job-fail.edn"
   :xf plain
   :cases fail-cases
   :gen (gen/tuple (gen/fmap #(assoc a-job :state (job/job-state %)) (gen/elements [:job/pending :job/ingesting :job/transcribing :job/translating :job/rendering :job/completed :job/failed])) (gen/return :error/asr-failed))
   :pred fail-pred
   :mutations [["missing-result" (fn [& _] nil)]]})


(def link-transcript-cases {:valid [a-job "ref"]})
(def link-transcript-pred (partial m/validate s/TranslationJob))
(deftest link-transcript-branches
  (doseq [[label args] link-transcript-cases]
    (testing (str label) (is (link-transcript-pred (apply job/link-transcript args))))))
(deftrifecta link-transcript job/link-transcript
  {:apply? true
   :golden-path "test/golden/domain/job-link-transcript.edn"
   :xf plain
   :cases link-transcript-cases
   :gen (gen/tuple (gen/return a-job) text-gen)
   :pred link-transcript-pred
   :mutations [["missing-result" (fn [& _] nil)]]})


(def link-subtitle-cases {:valid [a-job "ref"]})
(def link-subtitle-pred (partial m/validate s/TranslationJob))

(defn advance-observation [j]
  {:before j :result (job/advance j)})

(deftrifecta advance-relational advance-observation
  {:golden-path "test/golden/domain/job-advance-relational.edn"
   :xf plain
   :cases {:pending a-job :terminal (assoc a-job :state (job/job-state :job/completed))}
   :gen (gen/fmap #(assoc a-job :state (job/job-state %))
          (gen/elements [:job/pending :job/ingesting :job/transcribing :job/translating
                         :job/rendering :job/completed :job/failed]))
   :pred (fn [{:keys [before result]}]
           (let [from (get-in before [:state :adt/variant])
                 next-state {:job/pending :job/ingesting :job/ingesting :job/transcribing
                             :job/transcribing :job/translating :job/translating :job/rendering
                             :job/rendering :job/completed}]
             (and (result-valid? s/TranslationJob #{:error/illegal-transition} result)
                  (if-let [to (next-state from)]
                    (and (r/ok? result)
                         (= to (get-in result [:ok :state :adt/variant]))
                         (= (dissoc before :state) (dissoc (:ok result) :state)))
                    (and (= :error/illegal-transition (:error result))
                         (= from (:from result)))))))})
(deftest link-subtitle-branches
  (doseq [[label args] link-subtitle-cases]
    (testing (str label) (is (link-subtitle-pred (apply job/link-subtitle args))))))
(deftrifecta link-subtitle job/link-subtitle
  {:apply? true
   :golden-path "test/golden/domain/job-link-subtitle.edn"
   :xf plain
   :cases link-subtitle-cases
   :gen (gen/tuple (gen/return a-job) text-gen)
   :pred link-subtitle-pred
   :mutations [["missing-result" (fn [& _] nil)]]})

