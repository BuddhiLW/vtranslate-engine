(ns vtranslate.engine.collect.media-port-test
  "Bytedeco-FREE coverage for the Collect anti-corruption bridge. media-err
   remaps every Collect/fs error category onto the domain TranslationError ADT
   (golden + mutation), and CollectMediaPort re-runs the port.media contract
   over stub backends (ok / throwing / missing-source) — no JavaCV needed."
  (:require [clojure.test :refer [deftest is]]
            [hive-test.golden :refer [deftest-golden]]
            [hive-test.mutation :refer [deftest-mutations]]
            [hive-dsl.result :as r]
            [vtranslate.engine.port.media :as port]
            [vtranslate.engine.collect.protocols :as p]
            [vtranslate.engine.collect.media-port :as sut]))

;; a real, existing source (project root) + a guaranteed-missing one
(def ^:private existing-path "deps.edn")
(def ^:private missing-path  "/vtranslate/__no_such_file__.mp4")

;; =============================================================================
;; GOLDEN — every Collect/fs error category remaps to its domain ADT tag
;; =============================================================================

(def ^:private err-cases
  [{:error :collect/source-not-found :source-uri "/no.mp4"}
   {:error :fs/check-failed :source-uri "/no.mp4"}
   {:error :collect/probe-failed :reason "bad container"}
   {:error :collect/probe-timeout :reason "timed out"}
   {:error :collect/extract-failed :reason "no audio stream"}
   {:error :some/unknown-category :reason "passthrough"}])

(deftest-golden media-err-remap-golden
  "test/golden/collect-media-err.edn"
  (mapv (fn [e] (:error (sut/media-err "/uri.mp4" e))) err-cases))

;; =============================================================================
;; CONTRACT — CollectMediaPort satisfies port.media over an injected stub
;; =============================================================================

(def ^:private ok-backend
  (reify
    p/IMediaProbe
    (probe [_ _uri] {:container "mp4" :duration-ms 1000 :has-audio? true :audio-codec "aac"})
    p/IAudioExtractor
    (extract-audio [_ _uri out _opts] out)))

(deftest port-contract-over-stub-backend
  (let [mp (sut/collect-media-port ok-backend)]
    (is (satisfies? port/IMediaProbe mp))
    (is (satisfies? port/IAudioExtractor mp))
    (is (r/ok? (port/probe mp existing-path)))
    (is (r/ok? (port/extract-audio mp existing-path {})))))

;; =============================================================================
;; CONTRACT — the two error arms remap onto the domain TranslationError ADT
;; =============================================================================

(deftest missing-source-remaps-to-source-unreadable
  (let [mp  (sut/collect-media-port ok-backend)
        res (port/probe mp missing-path)]           ;; fs check fails first
    (is (r/err? res))
    (is (= :error/source-unreadable (:error res)))))

(deftest throwing-probe-remaps-to-probe-failed
  (let [boom (reify p/IMediaProbe
               (probe [_ _] (throw (ex-info "backend blew up" {}))))
        mp   (sut/collect-media-port boom)
        res  (port/probe mp existing-path)]          ;; source exists; backend throws
    (is (r/err? res))
    (is (= :error/probe-failed (:error res)))))

;; =============================================================================
;; MUTATION — break the remap seam, prove the contract catches it
;; =============================================================================

(deftest-mutations media-err-mutations-caught
  vtranslate.engine.collect.media-port/media-err
  [["passthrough-all"     (fn [_uri err] (r/err (:error err) {}))]
   ["always-probe-failed" (fn [_uri _err] (r/err :error/probe-failed {}))]]
  (fn []
    (is (= :error/source-unreadable   (:error (sut/media-err "/u" {:error :collect/source-not-found}))))
    (is (= :error/probe-failed        (:error (sut/media-err "/u" {:error :collect/probe-failed :reason "x"}))))
    (is (= :error/audio-extract-failed (:error (sut/media-err "/u" {:error :collect/extract-failed :reason "x"}))))))
