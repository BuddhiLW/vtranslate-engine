(ns vtranslate.engine.schema.calc-core
  "Non-runtime contracts for the pure batching, identity, coverage and path algebra.")

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
