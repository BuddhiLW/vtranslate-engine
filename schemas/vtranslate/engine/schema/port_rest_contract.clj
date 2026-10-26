(ns vtranslate.engine.schema.port-rest-contract
  "Test-only boundary contracts. Protocol receivers stay opaque; values do not.")

(def Span [:map [:start-ms :int] [:end-ms :int]])
(def Spans [:vector Span])
(def TextResult [:map [:ok :string]])
(def SpansResult [:map [:ok Spans]])
(def ComposeResult [:map [:ok [:map [:output-uri :string]]]])
(def ProbeResult [:map [:ok [:map [:duration-ms :int]]]])
(def SegmentResult [:map [:ok [:map [:spans Spans]]]])
(def FetchResult [:map [:ok [:map [:local-path :string]]]])
(def ParseResult [:map [:ok [:map [:cues [:vector [:map [:index :int] [:start-ms :int] [:end-ms :int] [:lines [:vector :string]]]]]]]])
(def HostLoaded [:map [:ok [:set :string]]])
(def HostUnload [:map [:ok [:enum :unloaded :busy :absent]]])
(def CountResult [:map [:ok :int]])
(def NativeDescription [:map [:strategy :keyword]])
(def Bool :boolean)
(def Failure [:map [:error :keyword]])
(def ProtocolResults
  [:map [:composer ComposeResult] [:probe ProbeResult] [:extract TextResult]
   [:source TextResult] [:segment SegmentResult] [:fetch FetchResult]
   [:render TextResult] [:parse ParseResult] [:burn :string]
   [:host-loaded HostLoaded] [:host-load TextResult] [:host-unload HostUnload]
   [:cache-fetch TextResult] [:cache-store TextResult] [:cache-forget TextResult]
   [:cache-evict CountResult] [:native TextResult] [:describe NativeDescription]
   [:covered SpansResult]])
