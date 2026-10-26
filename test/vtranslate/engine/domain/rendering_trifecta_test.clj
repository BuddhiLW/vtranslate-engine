(ns vtranslate.engine.domain.rendering-trifecta-test
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

(def cue-cases {:valid [cue-data] :start [(assoc cue-data :start-ms -1)] :end [(assoc cue-data :end-ms -1)] :inverted [(assoc cue-data :start-ms 20)] :index [(assoc cue-data :index 0)] :empty [(assoc cue-data :lines [])] :blank [(assoc cue-data :lines [" "])] :non-string [(assoc cue-data :lines [42])]})
(def cue-pred (partial result-valid? s/Cue #{:error/invalid-timecode :error/inverted-time-range :error/render-failed}))
(deftest cue-branches
  (doseq [[label args] cue-cases]
    (testing (str label) (is (cue-pred (apply render/make-cue args))))))
(deftrifecta cue render/make-cue
  {:apply? true
   :golden-path "test/golden/domain/rendering-cue.edn"
   :xf plain
   :cases cue-cases
   :gen (gen/tuple (gen/fmap #(merge cue-data %) (gen/hash-map :index (gen/elements [0 1 2]) :start-ms (gen/choose 0 20) :lines (gen/elements [[] [" "] [42] ["hello"] ["" "text"]]))))
   :pred cue-pred
   :mutations [["missing-result" (fn [& _] nil)]]})


(def subtitle-track-cases {:valid [{:language "en" :format :format/srt}] :language [{:language "xx" :format :format/srt}] :format [{:language "en" :format :bad}]})
(def subtitle-track-pred (partial result-valid? s/SubtitleTrack #{:error/unsupported-language :error/unsupported-format}))
(deftest subtitle-track-branches
  (doseq [[label args] subtitle-track-cases]
    (testing (str label) (is (subtitle-track-pred (apply render/make-subtitle-track args))))))
(deftrifecta subtitle-track render/make-subtitle-track
  {:apply? true
   :golden-path "test/golden/domain/rendering-subtitle-track.edn"
   :xf plain
   :cases subtitle-track-cases
   :gen (gen/tuple (gen/hash-map :id gen/int :source-id gen/int :language (gen/one-of [lang-gen (gen/return "xx")]) :format (gen/elements [:format/srt :format/vtt :format/ass :bad])))
   :pred subtitle-track-pred
   :mutations [["missing-result" (fn [& _] nil)]]})


(def add-cue-cases {:valid [track cue]})
(def add-cue-pred
  #(and (m/validate s/SubtitleTrack %)
        (= [cue] (:cues %))
        (= (dissoc track :cues) (dissoc % :cues))))
(deftest add-cue-branches
  (doseq [[label args] add-cue-cases]
    (testing (str label) (is (add-cue-pred (apply render/add-cue args))))))
(deftrifecta add-cue render/add-cue
  {:apply? true
   :golden-path "test/golden/domain/rendering-add-cue.edn"
   :xf plain
   :cases add-cue-cases
   :gen (gen/tuple (gen/return track) (gen/return cue))
   :pred add-cue-pred
   :mutations [["missing-result" (fn [& _] nil)]]})


(def render-cases {:valid [(render/add-cue track cue)] :empty [track] :rendered [(assoc track :status (render/track-status :track/rendered))] :failed [(assoc track :status (render/track-status :track/failed))]})
(def render-pred
  (fn [result]
    (and (result-valid? s/SubtitleTrack #{:error/render-failed :error/illegal-transition} result)
         (if (r/ok? result)
           (and (= :track/rendered (get-in result [:ok :status :adt/variant]))
                (seq (get-in result [:ok :cues])))
           (if (= :error/illegal-transition (:error result))
             (contains? #{:track/rendered :track/failed} (:from result))
             (= "no cues to render" (:reason result)))))))
(deftest render-branches
  (doseq [[label args] render-cases]
    (testing (str label) (is (render-pred (apply render/render args))))))
(deftrifecta render render/render
  {:apply? true
   :golden-path "test/golden/domain/rendering-render.edn"
   :xf plain
   :cases render-cases
   :gen (gen/tuple (gen/fmap (fn [[status cs]] (assoc track :status (render/track-status status) :cues cs)) (gen/tuple (gen/elements [:track/draft :track/rendered :track/failed]) (gen/elements [[] [cue]]))))
   :pred render-pred
   :mutations [["missing-result" (fn [& _] nil)]]})

