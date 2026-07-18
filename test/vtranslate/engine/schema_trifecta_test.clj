(ns vtranslate.engine.schema-trifecta-test
  "Free coverage: property + mutation facets synthesized from the malli value
   objects (hive-schemas.test). No hand-written generator, oracle, or mutant.
   Result constructors get a conformance + Result-invariant relation (an :or
   output has no corruptible common key, so mutation lives in the predicate
   facets); the pure value objects get their mutation teeth via the predicates."
  (:require [clojure.test :refer [deftest is]]
            [hive-dsl.result :as r]
            [hive-schemas.test :refer [deftrifecta-from-schema deftrifecta-predicate]]
            [vtranslate.engine.schema :as s]
            [vtranslate.engine.schema.contracts]
            [vtranslate.engine.schema.typed]
            [vtranslate.engine.shared :as shared]
            [vtranslate.engine.domain.transcription :as transcription]
            [vtranslate.engine.domain.rendering :as rendering]))

;; ---------------------------------------------------------------------------
;; Relations
;; ---------------------------------------------------------------------------

(defn- ok-of
  "Result-invariant relation: on {:ok v}, v satisfies `valid?`; on {:error k},
   k is a qualified keyword. Holds for EVERY input."
  [valid?]
  (fn [_in out]
    (if (contains? out :ok)
      (valid? (:ok out))
      (qualified-keyword? (:error out)))))

;; ---------------------------------------------------------------------------
;; Smart constructors — conformance + Result-invariant relation (:mutation off)
;; ---------------------------------------------------------------------------

(deftrifecta-from-schema make-timecode shared/make-timecode
  {:in s/Nat :out (s/result-of s/Timecode)
   :rel (ok-of s/timecode?) :mutation false})

(deftrifecta-from-schema make-language shared/make-language
  {:in s/Language :out (s/result-of s/Language)
   :rel (ok-of s/language?) :mutation false})

(deftrifecta-from-schema make-confidence transcription/make-confidence
  {:in [:double {:min 0.0 :max 1.0}] :out (s/result-of s/Confidence)
   :rel (ok-of s/confidence?) :mutation false})

(deftrifecta-from-schema make-segment transcription/make-segment
  {:in [:map [:index s/Nat] [:start-ms s/Nat] [:end-ms s/Nat]
        [:text :string] [:confidence [:double {:min 0.0 :max 1.0}]]
        [:language [:maybe s/Language]]]
   :out (s/result-of s/Segment)
   :rel (ok-of s/segment?) :mutation false})

(deftrifecta-from-schema make-cue rendering/make-cue
  {:in [:map [:index s/Nat] [:start-ms s/Nat] [:end-ms s/Nat]
        [:lines [:vector {:min 1} :string]]]
   :out (s/result-of s/Cue)
   :rel (ok-of s/cue?) :mutation false})

;; ---------------------------------------------------------------------------
;; Value-object predicates — positive (valid accepted) + negative (corrupted
;; rejected). This is where the value objects get their mutation teeth.
;; ---------------------------------------------------------------------------

(deftrifecta-predicate timecode-pred       s/timecode?       {:schema s/Timecode})
(deftrifecta-predicate time-range-pred     s/time-range?     {:schema s/TimeRange})
(deftrifecta-predicate confidence-pred     s/confidence?     {:schema s/Confidence})
(deftrifecta-predicate probe-info-pred     s/probe-info?     {:schema s/ProbeInfo})
(deftrifecta-predicate segment-pred        s/segment?        {:schema s/Segment})
(deftrifecta-predicate span-pred           s/span?           {:schema s/Span})
(deftrifecta-predicate window-pred         s/window?         {:schema s/Window})
(deftrifecta-predicate cue-data-pred       s/cue-data?       {:schema s/CueData})
(deftrifecta-predicate cue-pred            s/cue?            {:schema s/Cue})
(deftrifecta-predicate routing-config-pred s/routing-config? {:schema s/RoutingConfig})

;; ---------------------------------------------------------------------------
;; Hand-written invariants the schema shape can't express
;; ---------------------------------------------------------------------------

(deftest language-tags-match-shared
  (is (= (set s/language-tags) shared/supported-languages)
      "schema language enum stays in sync with the runtime registry"))

(deftest make-time-range-orders-endpoints
  (is (r/ok? (shared/make-time-range 0 100)))
  (is (r/ok? (shared/make-time-range 50 50)))
  (is (r/err? (shared/make-time-range 100 0))
      "inverted range is rejected"))
