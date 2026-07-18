(ns vtranslate.engine.domain.ingestion-test
  "MediaAsset ingestion — kind inference, smart ctor, subtitle ready path,
   ingress routing; plus the subtitle-path job/complete shortcut."
  (:require [clojure.test :refer [deftest is]]
            [hive-dsl.result :as r]
            [vtranslate.engine.domain.ingestion :as ingestion]
            [vtranslate.engine.domain.job :as job]))

(defn- subtitle-asset
  "A freshly registered :media/subtitle MediaAsset (probe nil)."
  []
  (:ok (ingestion/make-media-asset
        {:id "a" :source-uri "a.srt" :kind :media/subtitle})))

;; infer-kind maps subtitle extensions (case-insensitive) to :media/subtitle,
;; everything else (incl. missing ext / nil) defaults to :media/video.
(deftest infer-kind-routes-by-extension
  (is (= :media/subtitle (ingestion/infer-kind "x.srt")))
  (is (= :media/subtitle (ingestion/infer-kind "X.VTT")) "case-insensitive")
  (is (= :media/subtitle (ingestion/infer-kind "a.ass")))
  (is (= :media/video (ingestion/infer-kind "x.mp4")))
  (is (= :media/video (ingestion/infer-kind "noext")) "no extension -> video")
  (is (= :media/video (ingestion/infer-kind nil)) "nil -> video"))

;; Smart ctor accepts a known kind and returns an r/ok MediaAsset.
(deftest make-media-asset-ok-for-subtitle
  (let [res (ingestion/make-media-asset
             {:id "a" :source-uri "a.srt" :kind :media/subtitle})]
    (is (r/ok? res))
    (is (= :media/subtitle (get-in (:ok res) [:kind :adt/variant])))
    (is (= :asset/registered (get-in (:ok res) [:status :adt/variant])))))

;; Subtitles are always ready even with no probe.
(deftest subtitle-ready-without-probe
  (let [res (ingestion/ready (subtitle-asset))]
    (is (r/ok? res))
    (is (nil? (:probe (:ok res))) "no probe was attached")
    (is (= :asset/ready (get-in (:ok res) [:status :adt/variant])))))

;; A subtitle asset feeds the parse (no-ASR) ingress.
(deftest subtitle-ingress-is-parse
  (is (= :ingress/parse (ingestion/ingress-path (subtitle-asset)))))

;; complete jumps a pending job straight to the happy terminus.
(deftest complete-sets-job-completed
  (let [j (:ok (job/make-translation-job
                {:id "j" :asset-id "a" :target-language "en"}))]
    (is (= :job/pending (get-in j [:state :adt/variant])) "starts pending")
    (is (= :job/completed (get-in (:ok (job/complete j)) [:state :adt/variant])))))
