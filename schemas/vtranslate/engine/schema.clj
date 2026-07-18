(ns vtranslate.engine.schema
  "Malli value objects for the engine's kernel VOs + cross-boundary maps — the
   single source of truth for the m/=> contracts (vtranslate.engine.schema.contracts),
   the schema-derived tests, and the Typed Clojure annotations.

   NON-RUNTIME: on the :schemas extra-path, never the engine runtime src paths."
  (:require [malli.core :as m]
            [malli.registry :as mr]))

;; ---------------------------------------------------------------------------
;; Combinators
;; ---------------------------------------------------------------------------

(defn result-of
  "Malli schema for a hive-dsl Result carrying `val` on the ok side:
   {:ok val} | {:error qualified-keyword ...}. Open (err carries data)."
  [val]
  [:or
   [:map {:closed false} [:ok val]]
   [:map {:closed false} [:error :qualified-keyword]]])

(def Nat
  "Non-negative integer (milliseconds, indices, durations)."
  [:int {:min 0}])

;; ---------------------------------------------------------------------------
;; Language registry (mirrors vtranslate.engine.shared/supported-languages —
;; pinned by a coherence test so the two lists cannot drift)
;; ---------------------------------------------------------------------------

(def language-tags
  ["und" "en" "en-us" "pt" "pt-BR" "es" "es-419" "fr" "de" "ru"
   "zh" "zh-hans" "zh-cn" "zh-tw" "ja" "ar" "he" "fa"])

(def Language
  "A supported BCP-47 tag."
  (into [:enum] language-tags))

;; ---------------------------------------------------------------------------
;; Kernel value objects (vtranslate.engine.shared, .domain.transcription)
;; Records modeled OPEN + nilable-non-optional: a dropped key is a violation.
;; ---------------------------------------------------------------------------

(def Timecode
  "A point in time in whole milliseconds."
  [:map [:ms Nat]])

(def TimeRange
  "A start/end pair of Timecodes (start <= end enforced by the smart ctor)."
  [:map [:start Timecode] [:end Timecode]])

(def Confidence
  "An ASR confidence score in [0.0, 1.0]."
  [:map [:score [:double {:min 0.0 :max 1.0}]]])

;; ---------------------------------------------------------------------------
;; Cross-boundary maps
;; ---------------------------------------------------------------------------

(def ProbeInfo
  "IMediaProbe boundary facts about a media container."
  [:map
   [:container    :string]
   [:duration-ms  Nat]
   [:has-audio?   :boolean]
   [:audio-codec  [:maybe :string]]])

(def Segment
  "A transcription segment aggregate: 1-based index, range, text, confidence,
   optional source language."
  [:map
   [:index      Nat]
   [:range      TimeRange]
   [:text       :string]
   [:confidence Confidence]
   [:language   [:maybe Language]]])

(def Span
  "ISegmenter output span: a non-negative ms interval (start <= end enforced by
   the port contract)."
  [:map [:start-ms Nat] [:end-ms Nat]])

(def Spans
  "The ordered span sequence an ISegmenter ok result carries."
  [:sequential Span])

(def Window
  "calc.batching context window: a contiguous slice of texts with clamped
   before/after neighbour context."
  [:map
   [:texts  [:vector :string]]
   [:before [:vector :string]]
   [:after  [:vector :string]]])

(def CueData
  "ISubtitleParser boundary cue: 1-based index, ms bounds, 1+ text lines."
  [:map
   [:index    Nat]
   [:start-ms Nat]
   [:end-ms   Nat]
   [:lines    [:vector :string]]])

(def Cue
  "A domain display cue: index, a TimeRange, 1+ text lines."
  [:map
   [:index Nat]
   [:range TimeRange]
   [:lines [:vector :string]]])

(def RoutingConfig
  "providers.config/resolve-routing output: selected provider keywords + their
   option maps + addons. transcriber/translator are nilable (no implicit default)."
  [:map
   [:segmenter        :keyword]
   [:transcriber      [:maybe :keyword]]
   [:translator       [:maybe :keyword]]
   [:composer         :keyword]
   [:addons           [:vector :keyword]]
   [:segmenter-opts   :map]
   [:transcriber-opts :map]
   [:translator-opts  :map]
   [:composer-opts    :map]])

;; ---------------------------------------------------------------------------
;; Module-local registry (DIP seam) — never mutates the shared hive-spi atom
;; ---------------------------------------------------------------------------

(def schemas
  "The module-local {registry-key -> malli form} contribution."
  {:vtranslate.schema/language       Language
   :vtranslate.schema/timecode       Timecode
   :vtranslate.schema/time-range     TimeRange
   :vtranslate.schema/confidence     Confidence
   :vtranslate.schema/probe-info     ProbeInfo
   :vtranslate.schema/segment        Segment
   :vtranslate.schema/span           Span
   :vtranslate.schema/window         Window
   :vtranslate.schema/cue-data       CueData
   :vtranslate.schema/cue            Cue
   :vtranslate.schema/routing-config RoutingConfig})

(def registry
  "Composite registry: malli defaults + this module's schemas. Scoped to this
   module; the shared hive-spi registry is left untouched."
  (mr/composite-registry (m/default-schemas) schemas))

(defn schema
  "Compile `?s` (a :vtranslate.schema/* key or a malli form) against the module
   registry."
  [?s]
  (m/schema ?s {:registry registry}))

(defn validate
  "True iff `x` conforms to `?s` under the module registry."
  [?s x]
  (m/validate ?s x {:registry registry}))

(defn explain
  "malli explanation of `x` against `?s`, or nil when it conforms."
  [?s x]
  (m/explain ?s x {:registry registry}))

;; Value-object predicates (fully-qualified subjects for deftrifecta-predicate).

(defn timecode?       [x] (validate Timecode x))
(defn time-range?     [x] (validate TimeRange x))
(defn confidence?     [x] (validate Confidence x))
(defn language?       [x] (validate Language x))
(defn probe-info?     [x] (validate ProbeInfo x))
(defn segment?        [x] (validate Segment x))
(defn span?           [x] (validate Span x))
(defn window?         [x] (validate Window x))
(defn cue-data?       [x] (validate CueData x))
(defn cue?            [x] (validate Cue x))
(defn routing-config? [x] (validate RoutingConfig x))
