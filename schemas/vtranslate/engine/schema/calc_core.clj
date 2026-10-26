(ns vtranslate.engine.schema.calc-core
  "Non-runtime contracts for the pure batching, identity, coverage and path algebra."
  (:require [malli.core :as m]
            [vtranslate.engine.calc.batching :as b]
            [vtranslate.engine.calc.cache-key :as k]
            [vtranslate.engine.calc.coverage :as c]
            [vtranslate.engine.calc.paths :as p]
            [vtranslate.engine.calc.progress :as progress]
            [vtranslate.engine.calc.promote :as promote]))

(def Strings [:vector :string])
(def Index [:int {:min 0}])
(def Indexed [:vector [:tuple Index :string]])
(def Windows [:vector [:map [:texts Strings] [:before Strings] [:after Strings]]])
(def Groups [:map-of :boolean [:sequential [:tuple Index :int]]])
(def Payload [:map [:indices [:vector Index]] [:values [:vector :int]]])
(def StringResult [:or [:map [:ok Strings]] [:map [:error :keyword]]])
(def IndexedResult [:or [:map [:ok Indexed]] [:map [:error :keyword]]])
(def Sha256 [:re #"[0-9a-f]{64}"])
(def CacheFilename [:re #"[0-9a-f]{64}\.fressian"])
(def Span [:and [:map [:start-ms Index] [:end-ms Index]]
           [:fn (fn [{:keys [start-ms end-ms]}] (<= start-ms end-ms))]])
(def Spans [:vector Span])
(def Percent [:int {:min 0 :max 100}])
(def MaybePercent [:maybe Percent])
(def BothPaths [:map [:soft :string] [:hard :string]])
(def Cue [:map [:index [:int {:min 1}]]
          [:range [:map [:start [:map [:ms Index]]] [:end [:map [:ms Index]]]]]
          [:lines [:vector :string]]])
(def TrackResult [:or [:map [:ok [:map [:cues [:vector Cue]]]]]
                  [:map [:error :keyword]]])

;; Function schemas are opt-in registrations, never runtime instrumentation.
(m/=> vtranslate.engine.calc.batching/context-windows [:=> [:cat [:sequential :string] :int :int] Windows])
(m/=> vtranslate.engine.calc.batching/reassemble [:=> [:cat [:sequential StringResult]] StringResult])
(m/=> vtranslate.engine.calc.batching/index-groups [:=> [:cat [:sequential :any] ifn?] [:map-of :any [:sequential [:tuple Index :any]]]])
(m/=> vtranslate.engine.calc.batching/group-payload [:=> [:cat [:sequential [:tuple Index :any]] ifn?] [:map [:indices [:vector Index]] [:values [:vector :any]]]])
(m/=> vtranslate.engine.calc.batching/zip-indices [:=> [:cat [:sequential Index] [:sequential :any] ifn?] [:map]])
(m/=> vtranslate.engine.calc.batching/scatter [:=> [:cat Index [:sequential [:tuple Index :any]] ifn?] [:map]])
(m/=> vtranslate.engine.calc.cache-key/digest [:=> [:cat :any] Sha256])
(m/=> vtranslate.engine.calc.cache-key/transcript-key [:=> [:cat :map] Sha256])
(m/=> vtranslate.engine.calc.cache-key/cache-file-name [:=> [:cat :string] :string])
(m/=> vtranslate.engine.calc.coverage/gaps [:=> [:cat [:sequential Span] :int [:int {:min 1}]] Spans])
(m/=> vtranslate.engine.calc.coverage/tile [:=> [:cat Span :int] Spans])
(m/=> vtranslate.engine.calc.coverage/fill-gaps [:function
                 [:=> [:cat [:map [:spans Spans] [:duration-ms :int] [:window-ms :int] [:min-gap-ms [:int {:min 1}]]]] Spans]
                 [:=> [:cat Spans :int :int [:int {:min 1}]] Spans]])
(m/=> vtranslate.engine.calc.paths/sibling-output [:=> [:cat :string :string] :string])
(m/=> vtranslate.engine.calc.paths/language-variant [:=> [:cat :string [:maybe :string]] :string])
(m/=> vtranslate.engine.calc.paths/both-outputs [:=> [:cat :string] BothPaths])
(m/=> vtranslate.engine.calc.progress/floor-of [:=> [:cat [:maybe :keyword]] MaybePercent])
(m/=> vtranslate.engine.calc.progress/ceiling-of [:=> [:cat [:maybe :keyword]] Percent])
(m/=> vtranslate.engine.calc.progress/within-band [:=> [:cat [:maybe :keyword] :int :int] MaybePercent])
(m/=> vtranslate.engine.calc.promote/fill-cues [:=> [:cat [:map [:cues [:vector Cue]]] ifn? [:sequential :any]] TrackResult])
