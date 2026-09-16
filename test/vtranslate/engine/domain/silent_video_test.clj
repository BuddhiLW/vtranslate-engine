(ns vtranslate.engine.domain.silent-video-test
  "Silent-video extension for ready: {:allow-silent-video? true} accepts video
   with :has-video? true and no audio. Mutation coverage proves each invalid
   path is caught."
  (:require [clojure.test :refer [deftest is]]
            [hive-test.mutation :as mut]
            [hive-dsl.result :as r]
            [vtranslate.engine.domain.ingestion :as ingestion]))

;; --- probe constructors -----------------------------------------------------

(def ^:private silent-video-probe
  ":has-video? true, no audio — the silent-video opt-in scenario."
  (assoc (ingestion/->ProbeInfo "mp4" 60000 false nil) :has-video? true))

(def ^:private has-audio-probe
  "Plain video with audio, no video-capability key (legacy backend)."
  (ingestion/->ProbeInfo "mp4" 60000 true "aac"))

(def ^:private video-with-audio-probe
  "Video with both audio and explicit :has-video? true."
  (assoc (ingestion/->ProbeInfo "mp4" 60000 true "aac") :has-video? true))

(def ^:private no-capability-probe
  "No audio, no :has-video? key — legacy backend for video without audio."
  (ingestion/->ProbeInfo "mp4" 60000 false nil))

(def ^:private video-false-probe
  ":has-video? explicitly false — should NOT qualify even with opt-in."
  (assoc (ingestion/->ProbeInfo "mp4" 60000 false nil) :has-video? false))

(def ^:private audio-claiming-video-probe
  "No audio, but :has-video? true, on an AUDIO-kind asset. The only shape that
   separates the real guard from one that drops the kind check: every other
   rejection case here is already refused by the missing :has-video? key."
  (assoc (ingestion/->ProbeInfo "wav" 60000 false nil) :has-video? true))

;; --- asset builders ---------------------------------------------------------

(defn- mk-video [source-uri probe]
  (-> (:ok (ingestion/make-media-asset
            {:id "v1" :source-uri source-uri :kind :media/video}))
      (ingestion/with-probe probe)))

(defn- mk-audio [probe]
  (-> (:ok (ingestion/make-media-asset
            {:id "a1" :source-uri "a1.wav" :kind :media/audio}))
      (ingestion/with-probe probe)))

;; --- positive tests ---------------------------------------------------------

(deftest opted-in-silent-video-ok
  (let [asset  (mk-video "silent.mp4" silent-video-probe)
        result (ingestion/ready asset {:allow-silent-video? true})]
    (is (r/ok? result) "silent video with opt-in -> ok")
    (is (= :asset/ready (get-in (:ok result) [:status :adt/variant]))
        "asset marked ready")))

(deftest video-with-audio-legacy-ok
  (is (r/ok? (ingestion/ready (mk-video "talk.mp4" has-audio-probe)))
      "video with audio -> ok (legacy one-arity)"))

(deftest video-with-audio-and-has-video-ok
  (is (r/ok? (ingestion/ready (mk-video "talk2.mp4" video-with-audio-probe)
                              {:allow-silent-video? true}))
      "video with audio + :has-video? true -> ok (two-arity)"))

;; --- rejection tests --------------------------------------------------------

(deftest legacy-rejects-no-audio
  (is (r/err? (ingestion/ready (mk-video "silent.mp4" no-capability-probe)))
      "legacy one-arity rejects video without audio")
  (is (= :error/no-audio-stream
         (:error (ingestion/ready (mk-video "silent.mp4" no-capability-probe))))
      "error code is :error/no-audio-stream"))

(deftest opts-rejects-missing-has-video
  (is (r/err? (ingestion/ready (mk-video "silent.mp4" no-capability-probe)
                               {:allow-silent-video? true}))
      "allow-silent-video? with no :has-video? key -> err"))

(deftest opts-rejects-has-video-false
  (is (r/err? (ingestion/ready (mk-video "silent.mp4" video-false-probe)
                               {:allow-silent-video? true}))
      "allow-silent-video? with :has-video? false -> err"))

(deftest audio-kind-rejects-silent-opt
  (is (r/err? (ingestion/ready (mk-audio no-capability-probe)
                               {:allow-silent-video? true}))
      "audio kind cannot be opted-in via :allow-silent-video?"))

(deftest audio-kind-claiming-video-rejects-silent-opt
  (is (r/err? (ingestion/ready (mk-audio audio-claiming-video-probe)
                               {:allow-silent-video? true}))
      "audio kind with :has-video? true is still not a silent VIDEO"))

;; --- mutation test — prove invalid paths are caught -------------------------

(mut/deftest-mutations ready-silent-video-mutations-caught
  vtranslate.engine.domain.ingestion/ready
  [["always-ok: ignores all guards, returns :asset/ready unconditionally"
    (fn
      ([asset]
       (r/ok (assoc asset :status (ingestion/asset-status :asset/ready))))
      ([asset _opts]
       (r/ok (assoc asset :status (ingestion/asset-status :asset/ready)))))]
   ["ignore-opts: two-arity falls back to legacy (no silent-video)"
    (fn
      ([{:keys [kind probe] :as asset}]
       (if (or (= (:adt/variant kind) :media/subtitle)
               (:has-audio? probe))
         (r/ok (assoc asset :status (ingestion/asset-status :asset/ready)))
         (r/err :error/no-audio-stream {:source-id (str (:id asset))})))
      ([asset _opts]
       (let [{:keys [kind probe]} asset]
         (if (or (= (:adt/variant kind) :media/subtitle)
                 (:has-audio? probe))
           (r/ok (assoc asset :status (ingestion/asset-status :asset/ready)))
           (r/err :error/no-audio-stream {:source-id (str (:id asset))})))))]
   ["allow-audio-kind: :allow-silent-video? also passes audio-only assets"
    (fn
      ([{:keys [kind probe] :as asset}]
       (if (or (= (:adt/variant kind) :media/subtitle)
               (:has-audio? probe))
         (r/ok (assoc asset :status (ingestion/asset-status :asset/ready)))
         (r/err :error/no-audio-stream {:source-id (str (:id asset))})))
      ([{:keys [kind probe] :as asset} opts]
       ;; BUG: also allows audio kind when opt-in is set
       (if (or (= (:adt/variant kind) :media/subtitle)
               (:has-audio? probe)
               (and opts
                    (:allow-silent-video? opts)
                    ;; WRONG: accepts any non-subtitle kind, not just :media/video
                    (not= (:adt/variant kind) :media/subtitle)
                    (true? (:has-video? probe))))
         (r/ok (assoc asset :status (ingestion/asset-status :asset/ready)))
         (r/err :error/no-audio-stream {:source-id (str (:id asset))}))))]]
  (fn []
    ;; POSITIVE: opted-in silent video passes
    (let [res (ingestion/ready (mk-video "silent.mp4" silent-video-probe)
                               {:allow-silent-video? true})]
      (is (r/ok? res) "p1: silent video + allow-silent-video? -> ok"))
    ;; POSITIVE: video with audio (legacy) still works
    (is (r/ok? (ingestion/ready (mk-video "talk.mp4" has-audio-probe)))
        "p2: video with audio -> ok (legacy)")
    ;; NEGATIVE: legacy rejects video without audio
    (is (r/err? (ingestion/ready (mk-video "silent.mp4" no-capability-probe)))
        "n1: legacy rejects no-audio video")
    ;; NEGATIVE: opts with missing :has-video? rejects
    (is (r/err? (ingestion/ready (mk-video "silent.mp4" no-capability-probe)
                                 {:allow-silent-video? true}))
        "n2: missing :has-video? -> err")
    ;; NEGATIVE: opts with :has-video? false rejects
    (is (r/err? (ingestion/ready (mk-video "silent.mp4" video-false-probe)
                                 {:allow-silent-video? true}))
        "n3: :has-video? false -> err")
    ;; NEGATIVE: audio kind cannot opt in
    (is (r/err? (ingestion/ready (mk-audio no-capability-probe)
                                 {:allow-silent-video? true}))
        "n4: audio kind -> err even with :allow-silent-video?")
    ;; NEGATIVE: audio kind that CLAIMS :has-video? true. This is the assertion
    ;; that kills allow-audio-kind; without it the kind guard is unproven.
    (is (r/err? (ingestion/ready (mk-audio audio-claiming-video-probe)
                                 {:allow-silent-video? true}))
        "n5: audio kind + :has-video? true -> err")))
