(ns vtranslate.engine.contract.ports-contract
  "LSP contract suites — any implementation of a port protocol MUST satisfy these.
   Run here against conformant reference mocks; the M3 (whisper/translator/subtitle)
   and M5 (JavaCV media) adapters re-run the SAME check-* fns to prove they are
   substitutable behind the protocol (Liskov). The contract is the spec; a mock is
   just one passing impl."
  (:require [clojure.test :refer [deftest is]]
            [hive-dsl.result :as r]
            [vtranslate.engine.domain.rendering :as rd]
            [vtranslate.engine.port.media :as p.media]
            [vtranslate.engine.port.segmenter :as p.seg]
            [vtranslate.engine.port.transcriber :as p.asr]
            [vtranslate.engine.port.translator :as p.tr]
            [vtranslate.engine.port.subtitle :as p.sub]))

;; ---------------------------------------------------------------------------
;; Reusable contract assertions — parameterized over an impl (LSP). Reuse these
;; from each adapter's own test ns: (check-translator my-real-adapter ...).
;; ---------------------------------------------------------------------------

(defn check-media-probe
  "IMediaProbe: probe returns a Result; an ok carries all ProbeInfo keys."
  [impl uri]
  (let [res (p.media/probe impl uri)]
    (is (or (r/ok? res) (r/err? res)) "probe returns a hive-dsl Result")
    (when (r/ok? res)
      (let [m (:ok res)]
        (is (every? #(contains? m %) [:container :duration-ms :has-audio? :audio-codec])
            "ok probe carries all ProbeInfo keys")
        (is (boolean? (:has-audio? m)) ":has-audio? is boolean")
        (is (nat-int? (:duration-ms m)) ":duration-ms is a non-negative int")))))

(defn check-audio-extractor
  "IAudioExtractor: extract-audio returns a Result."
  [impl uri]
  (let [res (p.media/extract-audio impl uri {})]
    (is (or (r/ok? res) (r/err? res)) "extract-audio returns a Result")))

(defn check-segmenter
  "ISegmenter: segment returns a Result; ok spans are ordered, non-overlapping,
   each with start<=end."
  [impl audio]
  (let [res (p.seg/segment impl audio {})]
    (is (or (r/ok? res) (r/err? res)) "segment returns a hive-dsl Result")
    (when (r/ok? res)
      (let [spans (:spans (:ok res))]
        (is (sequential? spans) "ok carries a :spans sequence")
        (is (every? (fn [s] (<= (:start-ms s) (:end-ms s))) spans)
            "every span has start-ms <= end-ms")
        (is (->> spans (partition 2 1) (every? (fn [[a b]] (<= (:end-ms a) (:start-ms b)))))
            "spans are ordered and non-overlapping")))))

(defn check-transcriber
  "ITranscriber: transcribe returns a Result; ok segments have start<=end + string text."
  [impl audio language]
  (let [res (p.asr/transcribe impl audio language {})]
    (is (or (r/ok? res) (r/err? res)) "transcribe returns a Result")
    (when (r/ok? res)
      (let [segs (:segments (:ok res))]
        (is (sequential? segs) "ok carries a :segments sequence")
        (is (every? (fn [s] (and (<= (:start-ms s) (:end-ms s)) (string? (:text s)))) segs)
            "every segment has start<=end and string text")))))

(defn check-translator
  "ITranslator: translate-batch returns a Result; ok preserves count + order."
  [impl texts source-language target-language]
  (let [res (p.tr/translate-batch impl texts source-language target-language {})]
    (is (or (r/ok? res) (r/err? res)) "translate-batch returns a Result")
    (when (r/ok? res)
      (is (= (count texts) (count (:ok res))) "translation count == input count")
      (is (every? string? (:ok res)) "all translations are strings"))))

(defn check-renderer
  "ISubtitleRenderer: render-bytes returns a Result<string>."
  [impl subtitle-track]
  (let [res (p.sub/render-bytes impl subtitle-track)]
    (is (or (r/ok? res) (r/err? res)) "render-bytes returns a Result")
    (when (r/ok? res) (is (string? (:ok res)) "ok render is a string"))))

;; ---------------------------------------------------------------------------
;; Conformant reference impls (one passing impl per port).
;; ---------------------------------------------------------------------------

(def ref-media
  (reify
    p.media/IMediaProbe
    (probe [_ _uri]
      (r/ok {:container "mp4" :duration-ms 2000 :has-audio? true :audio-codec "aac"}))
    p.media/IAudioExtractor
    (extract-audio [_ _uri _opts]
      (r/ok {:path "/tmp/a.wav" :sample-rate 16000}))))

(def ref-segmenter
  (reify p.seg/ISegmenter
    (segment [_ _audio _opts]
      (r/ok {:spans [{:start-ms 0 :end-ms 1000} {:start-ms 1000 :end-ms 2000}]}))))

(def ref-transcriber
  (reify p.asr/ITranscriber
    (transcribe [_ _audio _lang _opts]
      (r/ok {:segments [{:start-ms 0 :end-ms 1000 :text "hello" :confidence 0.9}]}))))

(def ref-translator
  (reify p.tr/ITranslator
    (translate-batch [_ texts _src _tgt _opts]
      (r/ok (mapv #(str % "-x") texts)))))

(def ref-renderer
  (reify p.sub/ISubtitleRenderer
    (render-bytes [_ track]
      (r/ok (pr-str (mapv :lines (:cues track)))))))

(defn- sample-track []
  (let [t (:ok (rd/make-subtitle-track {:id "s" :source-id "c" :language "pt-BR" :format :format/srt}))
        c (:ok (rd/make-cue {:index 1 :start-ms 0 :end-ms 1000 :lines ["oi"]}))]
    (:ok (rd/render (rd/add-cue t c)))))

;; ---------------------------------------------------------------------------
;; Contract tests against the reference impls.
;; ---------------------------------------------------------------------------

(deftest media-probe-contract     (check-media-probe ref-media "/v.mp4"))
(deftest audio-extractor-contract (check-audio-extractor ref-media "/v.mp4"))
(deftest segmenter-contract       (check-segmenter ref-segmenter {:path "/tmp/a.wav" :duration-ms 2000}))
(deftest transcriber-contract     (check-transcriber ref-transcriber {:path "/tmp/a.wav"} "en"))
(deftest translator-contract      (check-translator ref-translator ["a" "b" "c"] "en" "pt-BR"))
(deftest renderer-contract        (check-renderer ref-renderer (sample-track)))
