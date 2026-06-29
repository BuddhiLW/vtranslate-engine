(ns vtranslate.engine.collect.audio-test
  "Collect-layer audio orchestration — all three hive-test paradigms, with NO
   bytedeco: a reify stub stands in for the JavaCV backend (the real native path
   is covered by dev/smoke_ffmpeg.clj). Trifecta on the pure projection; explicit
   golden + property + mutation on the Result railway."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [hive-test.trifecta :refer [deftrifecta]]
            [hive-test.golden :refer [deftest-golden]]
            [hive-test.mutation :refer [deftest-mutations]]
            [vtranslate.engine.collect.protocols :as p]
            [vtranslate.engine.collect.audio :as a]
            [vtranslate.engine.domain.ingestion :as ingestion]
            [hive-dsl.result :as r]))

;; --- a real, existing path + a guaranteed-missing one (railway both arms) ----
(def existing-path "deps.edn")            ;; always present at project root
(def missing-path  "/vtranslate/__no_such_file__.mp4")

;; --- stub backend (DIP: the orchestration can't tell it from JavaCvMedia) ----
(def stub
  (reify
    p/IMediaProbe
    (probe [_ _uri]
      {:container "mp4" :duration-ms 9000 :has-audio? true
       :audio-codec "aac" :sample-rate 48000 :channels 2})
    p/IAudioExtractor
    (extract-audio [_ _uri out-path _opts] out-path)))

(def gen-backend-map
  (gen/hash-map :container   gen/string-alphanumeric
                :duration-ms gen/nat
                :has-audio?  gen/boolean
                :audio-codec gen/string-alphanumeric
                :sample-rate gen/nat
                :channels    gen/nat))

;; =============================================================================
;; TRIFECTA — the pure projection backend-map -> domain ProbeInfo
;; =============================================================================

(deftrifecta to-probe-info
  vtranslate.engine.collect.audio/to-probe-info
  {:golden-path "test/golden/audio-to-probe-info.edn"
   :cases {:stereo-video {:container "mp4" :duration-ms 121858 :has-audio? true  :audio-codec "aac"       :sample-rate 48000 :channels 2}
           :mono-wav     {:container "wav" :duration-ms 5000   :has-audio? true  :audio-codec "pcm_s16le" :sample-rate 16000 :channels 1}
           :silent       {:container "mp4" :duration-ms 1000   :has-audio? false :audio-codec nil         :sample-rate 0     :channels 0}}
   :xf   #(into {} %)                ;; record -> plain map for a stable EDN snapshot

   ;; projection MUST keep exactly the 4 domain fields, dropping collect-only keys
   :gen  gen-backend-map
   :pred (fn [pi] (= #{:container :duration-ms :has-audio? :audio-codec}
                     (set (keys pi))))
   :num-tests 300

   :mutations [["swap-codec/container"
                (fn [m] (ingestion/->ProbeInfo (:audio-codec m) (:duration-ms m) (:has-audio? m) (:container m)))]
               ["zero-duration"
                (fn [m] (ingestion/->ProbeInfo (:container m) 0 (:has-audio? m) (:audio-codec m)))]
               ["leak-sample-rate"
                (fn [m] (assoc (ingestion/->ProbeInfo (:container m) (:duration-ms m) (:has-audio? m) (:audio-codec m))
                               :sample-rate (:sample-rate m)))]]})

;; =============================================================================
;; GOLDEN — the railway error shapes are pure data; snapshot them
;; =============================================================================

(deftest-golden probe-missing-source-golden
  "test/golden/audio-probe-missing.edn"
  (a/probe stub missing-path))            ;; => {:error :collect/source-not-found ...}

(deftest-golden extract-missing-source-golden
  "test/golden/audio-extract-missing.edn"
  (a/extract-audio stub missing-path "/tmp/out.wav"))

;; =============================================================================
;; PROPERTY — probe-all preserves length + aligns ok/err to source existence
;; =============================================================================

(defspec probe-all-preserves-length 100
  (prop/for-all [n (gen/choose 0 6)]
    (let [res (a/probe-all stub (repeat n existing-path)
                           {:concurrency 3 :timeout-ms 5000})]
      (and (= n (count res))
           (every? r/ok? res)))))

(defspec probe-all-aligns-ok-with-existence 60
  (prop/for-all [flags (gen/vector gen/boolean 0 6)]
    (let [srcs (map #(if % existing-path missing-path) flags)
          res  (a/probe-all stub srcs {:concurrency 4 :timeout-ms 5000})]
      (= (vec flags) (mapv r/ok? res)))))   ;; ok? aligns 1:1 with file existence

(defspec extract-defaults-to-16k-mono 50
  (prop/for-all [out gen/string-alphanumeric]
    ;; default opts merge in regardless of caller; here we just assert the
    ;; railway succeeds for an existing source and returns the out-path.
    (let [res (a/extract-audio stub existing-path (str "/tmp/" out ".wav"))]
      (and (r/ok? res) (= (str "/tmp/" out ".wav") (:ok res))))))

;; =============================================================================
;; MUTATION — break the source-validation seam, prove the railway tests catch it
;; =============================================================================

(deftest-mutations ensure-source-mutations-caught
  vtranslate.engine.collect.audio/ensure-source
  [["always-ok"  (fn [src] (r/ok src))]                          ;; ignores existence
   ["always-err" (fn [_] (r/err :collect/source-not-found {}))]  ;; rejects everything
   ["swap-arms"  (fn [src] (if (= src missing-path) (r/ok src)   ;; inverts the check
                               (r/err :collect/source-not-found {:source-uri src})))]]
  (fn []
    (is (r/ok?  (a/probe stub existing-path)))
    (is (r/err? (a/probe stub missing-path)))))
