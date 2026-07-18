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
            [vtranslate.engine.port.subtitle :as p.sub]
            [vtranslate.engine.port.composer :as p.comp]
            [vtranslate.engine.schema :as schema]))

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
  "ITranscriber: transcribe returns a Result. An ok carries :segments that are
   ordered, non-overlapping, each start<=end with string text. An err carries the
   contract's :error/asr-failed tag (fail-loud — never a silent/fake transcript)."
  [impl audio language]
  (let [res (p.asr/transcribe impl audio language {})]
    (is (or (r/ok? res) (r/err? res)) "transcribe returns a Result")
    (if (r/ok? res)
      (let [segs (:segments (:ok res))]
        (is (sequential? segs) "ok carries a :segments sequence")
        (is (every? (fn [s] (and (<= (:start-ms s) (:end-ms s)) (string? (:text s)))) segs)
            "every segment has start<=end and string text")
        (is (->> segs (partition 2 1) (every? (fn [[a b]] (<= (:end-ms a) (:start-ms b)))))
            "segments are ordered and non-overlapping"))
      (is (= :error/asr-failed (:error res)) "err carries the :error/asr-failed tag"))))

(defn check-translator
  "ITranslator: translate-batch returns a Result. An ok preserves count + order
   (positional alignment, 1:1 with inputs). An err carries the contract's
   :error/translation-failed tag."
  [impl texts source-language target-language]
  (let [res (p.tr/translate-batch impl texts source-language target-language {})]
    (is (or (r/ok? res) (r/err? res)) "translate-batch returns a Result")
    (if (r/ok? res)
      (do
        (is (= (count texts) (count (:ok res))) "translation count == input count")
        (is (every? string? (:ok res)) "all translations are strings"))
      (is (= :error/translation-failed (:error res))
          "err carries the :error/translation-failed tag"))))

(defn check-renderer
  "ISubtitleRenderer: render-bytes returns a Result<string>."
  [impl subtitle-track]
  (let [res (p.sub/render-bytes impl subtitle-track)]
    (is (or (r/ok? res) (r/err? res)) "render-bytes returns a Result")
    (when (r/ok? res) (is (string? (:ok res)) "ok render is a string"))))

(defn check-composer
  "IVideoComposer: compose returns a Result. An ok carries a non-blank string
   :output-uri. An err carries the contract's :error/compose-failed tag (fail-loud
   — never a silent success)."
  [impl video-source subtitle-track opts]
  (let [res (p.comp/compose impl video-source subtitle-track opts)]
    (is (or (r/ok? res) (r/err? res)) "compose returns a hive-dsl Result")
    (if (r/ok? res)
      (let [out (:output-uri (:ok res))]
        (is (string? out) "ok carries a string :output-uri")
        (is (seq out) ":output-uri is non-blank"))
      (is (= :error/compose-failed (:error res)) "err carries the :error/compose-failed tag"))))

(defn check-parser
  "ISubtitleParser: parse returns a Result. An ok carries :cues that each conform
   to the CueData schema, in non-decreasing index order. An err carries the
   contract's :error/unsupported-format tag."
  [impl text format]
  (let [res (p.sub/parse impl text format)]
    (is (or (r/ok? res) (r/err? res)) "parse returns a hive-dsl Result")
    (if (r/ok? res)
      (let [cues (:cues (:ok res))]
        (is (sequential? cues) "ok carries a :cues sequence")
        (is (every? #(schema/validate schema/CueData %) cues)
            "every cue conforms to the CueData schema")
        (is (->> cues (partition 2 1) (every? (fn [[a b]] (<= (:index a) (:index b)))))
            "cues are in non-decreasing index order"))
      (is (= :error/unsupported-format (:error res))
          "err carries the :error/unsupported-format tag"))))

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

(def ref-composer
  (reify p.comp/IVideoComposer
    (compose [_ _video-source _track opts]
      (r/ok {:output-uri (or (:output-uri opts) "/tmp/v.subbed.mp4")}))))

(def ref-parser
  (reify p.sub/ISubtitleParser
    (parse [_ _text _format]
      (r/ok {:cues [{:index 1 :start-ms 0 :end-ms 1000 :lines ["oi"]}
                    {:index 2 :start-ms 1000 :end-ms 2000 :lines ["tchau"]}]}))))

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

(deftest composer-contract        (check-composer ref-composer "/v.mp4" (sample-track) {:output-uri "/tmp/x.subbed.mp4"}))

(deftest parser-contract          (check-parser ref-parser "1\n00:00:00,000 --> 00:00:01,000\noi\n" :format/srt))