(ns vtranslate.engine.shared
  "Shared kernel — value objects reused by every bounded context: Language
   (BCP-47), Timecode, TimeRange. Pure data + smart constructors; no effects,
   no engine deps. Bottom of the stratified stack: everything may depend here,
   it depends on nothing above."
  (:require [clojure.string :as str]
            [hive-dsl.result :as r]))

;; --- SourceRef (locator value object) --------------------------------------

(defrecord SourceRef [uri])         ; uri : non-blank locator (fs path or URI)

(defn make-source-ref
  "Validate a source locator (a non-blank string — fs path or URI). The single
   value object both MediaAsset and the job-orchestration layer use to name the
   artifact, instead of passing a raw string around.
   => (r/ok SourceRef) | (r/err :error/invalid-source {:source uri})."
  [uri]
  (if (and (string? uri) (not (str/blank? uri)))
    (r/ok (->SourceRef uri))
    (r/err :error/invalid-source {:source uri})))

;; --- Language (BCP-47 tag, closed registry) --------------------------------

(def supported-languages
  "Closed registry of supported language tags (BCP-47). Mutation surface - keep
   in sync with translator/ASR adapter capability tables."
  #{"und" "en" "en-us" "pt" "pt-BR" "es" "es-419" "fr" "de" "ru"
    "zh" "zh-hans" "zh-cn" "zh-tw" "ja" "ar" "he" "fa"})

(defn make-language
  "Validate a BCP-47 tag against the registry.
   => (r/ok tag) | (r/err :error/unsupported-language {:language tag})."
  [tag]
  (if (contains? supported-languages tag)
    (r/ok tag)
    (r/err :error/unsupported-language {:language tag})))

;; --- Time (value objects) --------------------------------------------------

(defrecord Timecode [ms])           ; ms : non-negative integer milliseconds
(defrecord TimeRange [start end])   ; start/end : Timecode, invariant start <= end

(defn make-timecode
  "=> (r/ok Timecode) for a non-negative integer ms, else (r/err ...)."
  [ms]
  (if (nat-int? ms)
    (r/ok (->Timecode ms))
    (r/err :error/invalid-timecode {:ms ms})))

(defn make-time-range
  "=> (r/ok TimeRange) when 0 <= start <= end, else (r/err ...)."
  [start-ms end-ms]
  (r/let-ok [start (make-timecode start-ms)
             end   (make-timecode end-ms)]
            (if (<= (:ms start) (:ms end))
              (r/ok (->TimeRange start end))
              (r/err :error/inverted-time-range {:start start-ms :end end-ms}))))

(defn duration-ms
  "Length of a TimeRange in milliseconds (a pure calculation)."
  [{:keys [start end]}]
  (- (:ms end) (:ms start)))
