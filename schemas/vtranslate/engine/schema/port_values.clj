(ns vtranslate.engine.schema.port-values
  "Non-runtime port DATA contracts. Transport objects deliberately stay opaque."
  (:require [vtranslate.engine.schema :as s]))

(defn ordered? [xs]
  (and (every? #(<= (:start-ms %) (:end-ms %)) xs)
       (every? (fn [[a b]] (<= (:end-ms a) (:start-ms b))) (partition 2 1 xs))))

(def Span [:and s/Span [:fn #(<= (:start-ms %) (:end-ms %))]])
(def Spans [:and [:vector Span] [:fn ordered?]])
(def RawSegment
  [:and
   [:map [:start-ms s/Nat] [:end-ms s/Nat] [:text [:string {:min 1}]]
    [:confidence [:double {:min 0.0 :max 1.0}]]
    [:language {:optional true} :string]]
   [:fn #(<= (:start-ms %) (:end-ms %))]])
(def Segments [:and [:vector RawSegment] [:fn ordered?]])
(def Transcription [:map [:segments Segments]])
(def Segmentation [:map [:spans Spans]])
(def ParsedSubtitle [:map [:cues [:vector s/CueData]]])
(def RenderedSubtitle :string)
(def Translation [:vector :string])
(def FetchValue
  [:map [:local-path :string] [:title {:optional true} [:maybe :string]]
   [:metadata {:optional true} [:maybe :map]]])
(def Composition
  [:or [:map [:output-uri :string]]
   [:map [:output-uris [:map-of :keyword :string]]]])
(def BurnValue :string)
(def LoadedModels [:set :string])
(def LoadedModel :string)
(def UnloadedModel [:enum :unloaded :busy :absent])
(def Transcript
  [:map [:segments [:vector s/Segment]]
   [:source-language {:optional true} :string]])
(def CacheHit [:maybe Transcript])
(def CacheKey :string)
(def EvictedCount s/Nat)

;; Unlike the historical result-of, prohibit ambiguous success+error maps.
(defn result-of [value]
  [:and (s/result-of value)
   [:fn #(not (and (contains? % :ok) (contains? % :error)))]] )

(def TranscribeResult (result-of Transcription))
(def SegmentResult (result-of Segmentation))
(def ParseResult (result-of ParsedSubtitle))
(def RenderResult (result-of RenderedSubtitle))
(def TranslateResult (result-of Translation))
(def FetchResult (result-of FetchValue))
(def ComposeResult (result-of Composition))
(def LoadedResult (result-of LoadedModels))
(def LoadResult (result-of LoadedModel))
(def UnloadResult (result-of UnloadedModel))
(def CacheFetchResult (result-of CacheHit))
(def CacheWriteResult (result-of CacheKey))
(def CacheEvictResult (result-of EvictedCount))
