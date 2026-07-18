(ns vtranslate.engine.schema.contracts
  "Contract spine — attaches malli function schemas (m/=>) to the kernel + domain
   smart constructors and the pure boundary calculations.

   NON-RUNTIME: loaded only under the :schemas / :test aliases; requiring it
   never changes engine-core runtime behaviour."
  (:require [malli.core :as m]
            [vtranslate.engine.schema :as s]
            [vtranslate.engine.shared :as shared]
            [vtranslate.engine.domain.transcription :as transcription]
            [vtranslate.engine.domain.rendering :as rendering]
            [vtranslate.engine.calc.batching :as batching]
            [vtranslate.engine.providers.config :as config]))

;; ---------------------------------------------------------------------------
;; Constructor argument schemas
;; ---------------------------------------------------------------------------

(def MakeSegmentArgs
  [:map
   [:index      s/Nat]
   [:start-ms   :any]
   [:end-ms     :any]
   [:text       [:maybe :string]]
   [:confidence :any]
   [:language   [:maybe :string]]])

(def MakeCueArgs
  [:map
   [:index    s/Nat]
   [:start-ms :any]
   [:end-ms   :any]
   [:lines    [:maybe [:sequential :string]]]])

;; ---------------------------------------------------------------------------
;; Contract spine
;; ---------------------------------------------------------------------------

(m/=> shared/make-timecode   [:=> [:cat :any] (s/result-of s/Timecode)])
(m/=> shared/make-time-range [:=> [:cat :any :any] (s/result-of s/TimeRange)])
(m/=> shared/make-language   [:=> [:cat :any] (s/result-of s/Language)])

(m/=> transcription/make-confidence [:=> [:cat :any] (s/result-of s/Confidence)])
(m/=> transcription/make-segment    [:=> [:cat MakeSegmentArgs] (s/result-of s/Segment)])

(m/=> rendering/make-cue [:=> [:cat MakeCueArgs] (s/result-of s/Cue)])

(m/=> batching/context-windows
      [:=> [:cat [:sequential :string] :int :int] [:vector s/Window]])

(m/=> config/resolve-routing
      [:function
       [:=> [:cat] (s/result-of s/RoutingConfig)]
       [:=> [:cat :map] (s/result-of s/RoutingConfig)]])
