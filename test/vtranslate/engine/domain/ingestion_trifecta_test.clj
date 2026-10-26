(ns vtranslate.engine.domain.ingestion-trifecta-test
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

(def media-asset-cases {:valid [{:id "a" :source-uri "a" :kind :media/video}] :kind [{:kind :bad :source-uri "a"}] :source [{:kind :media/audio :source-uri " "}]})
(def media-asset-pred (partial result-valid? s/MediaAsset #{:error/unsupported-format :error/invalid-source}))
(deftest media-asset-branches
  (doseq [[label args] media-asset-cases]
    (testing (str label) (is (media-asset-pred (apply ing/make-media-asset args))))))
(deftrifecta media-asset ing/make-media-asset
  {:apply? true
   :golden-path "test/golden/domain/ingestion-media-asset.edn"
   :xf plain
   :cases media-asset-cases
   :gen (gen/tuple (gen/hash-map :id gen/int :source-uri (gen/one-of [text-gen (gen/elements [nil " " 42])]) :kind (gen/elements [:media/video :media/audio :media/subtitle :bad nil])))
   :pred media-asset-pred
   :mutations [["missing-result" (fn [& _] nil)]]})


(def with-probe-cases {:valid [asset {:container "mp4" :duration-ms 10 :has-audio? true :audio-codec "aac"}]})
(def with-probe-pred (partial m/validate s/MediaAsset))
(deftest with-probe-branches
  (doseq [[label args] with-probe-cases]
    (testing (str label) (is (with-probe-pred (apply ing/with-probe args))))))
(deftrifecta with-probe ing/with-probe
  {:apply? true
   :golden-path "test/golden/domain/ingestion-with-probe.edn"
   :xf plain
   :cases with-probe-cases
   :gen (gen/tuple (gen/return asset) (gen/hash-map :container text-gen :duration-ms time-gen :has-audio? gen/boolean :audio-codec text-gen))
   :pred with-probe-pred
   :mutations [["missing-result" (fn [& _] nil)]]})


(def ready-cases {:audio [(assoc asset :probe {:container "mp4" :duration-ms 10 :has-audio? true :audio-codec "aac"})] :no-audio [asset] :subtitle [(assoc asset :kind (ing/media-kind :media/subtitle))] :silent-video [(assoc asset :probe {:container "mp4" :duration-ms 10 :has-audio? false :has-video? true :audio-codec nil}) {:allow-silent-video? true}] :not-video [asset {:allow-silent-video? true}]})
(def ready-pred
  (fn [result]
    (and (result-valid? s/MediaAsset #{:error/no-audio-stream} result)
         (if (r/ok? result)
           (= :asset/ready (get-in result [:ok :status :adt/variant]))
           (= "a" (:source-id result))))))
(deftest ready-branches
  (doseq [[label args] ready-cases]
    (testing (str label) (is (ready-pred (apply ing/ready args))))))
(deftrifecta ready ing/ready
  {:apply? true
   :golden-path "test/golden/domain/ingestion-ready.edn"
   :xf plain
   :cases ready-cases
   :gen (gen/fmap (fn [[kind audio video allow]] [(assoc asset :kind (ing/media-kind kind) :probe {:container "x" :duration-ms 1 :has-audio? audio :has-video? video :audio-codec nil}) {:allow-silent-video? allow}]) (gen/tuple (gen/elements [:media/video :media/audio :media/subtitle]) gen/boolean gen/boolean gen/boolean))
   :pred ready-pred
   :mutations [["missing-result" (fn [& _] nil)]]})


(def reject-cases {:valid [asset] :again [(ing/reject asset)]})
(def reject-pred
  #(and (m/validate s/MediaAsset %)
        (= :asset/rejected (get-in % [:status :adt/variant]))
        (= (dissoc asset :status) (dissoc % :status))))
(deftest reject-branches
  (doseq [[label args] reject-cases]
    (testing (str label) (is (reject-pred (apply ing/reject args))))))
(deftrifecta reject ing/reject
  {:apply? true
   :golden-path "test/golden/domain/ingestion-reject.edn"
   :xf plain
   :cases reject-cases
   :gen (gen/tuple (gen/return asset))
   :pred reject-pred
   :mutations [["missing-result" (fn [& _] nil)]]})

