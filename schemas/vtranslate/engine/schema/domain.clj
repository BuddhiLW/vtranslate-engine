(ns vtranslate.engine.schema.domain
  "Non-runtime contracts for domain values. Open maps preserve addon annotations.
   IDs deliberately remain unconstrained: domain constructors do not validate them."
  (:require [clojure.string :as str]
            [vtranslate.engine.shared :as shared]))

(def NonBlank [:and :string [:fn (complement str/blank?)]])
(def Language (into [:enum] shared/supported-languages))
(def SourceLanguage (into [:enum] shared/source-languages))
(def TargetLanguage (into [:enum] shared/target-languages))
(def SourceRef [:map [:uri NonBlank]])
(def Timecode [:map [:ms nat-int?]])
(def TimeRange
  [:and [:map [:start Timecode] [:end Timecode]]
   [:fn #(<= (get-in % [:start :ms]) (get-in % [:end :ms]))]])
(def Confidence [:map [:score [:double {:min 0.0 :max 1.0}]]])
(defn variant [& variants] [:map [:adt/variant (into [:enum] variants)]])
(def MediaKind (variant :media/video :media/audio :media/subtitle))
(def AssetStatus (variant :asset/registered :asset/probed :asset/ready :asset/rejected))
(def ProbeInfo
  [:map [:container :any] [:duration-ms :any] [:has-audio? :boolean] [:audio-codec :any]])
(def MediaAsset
  [:map [:id :any] [:source-ref SourceRef] [:kind MediaKind]
   [:status AssetStatus] [:probe [:maybe ProbeInfo]]])
(def Segment
  [:map [:index pos-int?] [:range TimeRange] [:text NonBlank]
   [:confidence Confidence] [:language [:maybe Language]]])
(def TranscriptStatus
  (variant :transcript/empty :transcript/partial :transcript/complete :transcript/silent :transcript/failed))
(def Transcript
  [:map [:id :any] [:asset-id :any] [:language Language]
   [:segments [:vector Segment]] [:status TranscriptStatus]])
(def TranslationUnit
  [:map [:range TimeRange] [:source-language [:maybe Language]]
   [:source-text :any] [:target-text NonBlank]])
(def TranslationStatus
  (variant :translation/pending :translation/translating :translation/complete :translation/failed))
(def TranslatedCues
  [:and [:map [:id :any] [:transcript-id :any] [:source-language Language]
         [:target-language Language] [:units [:vector TranslationUnit]] [:status TranslationStatus]]
   [:fn #(not= (:source-language %) (:target-language %))]])
(def Cue
  [:map [:index pos-int?] [:range TimeRange]
   [:lines [:and [:vector :string] [:fn #(boolean (some (complement str/blank?) %))]]]])
(def SubtitleFormat (variant :format/srt :format/vtt :format/ass))
(def TrackStatus (variant :track/draft :track/rendered :track/failed))
(def SubtitleTrack
  [:map [:id :any] [:source-id :any] [:language Language] [:format SubtitleFormat]
   [:cues [:vector Cue]] [:status TrackStatus]])
(def JobState
  (variant :job/pending :job/ingesting :job/transcribing :job/translating
           :job/rendering :job/completed :job/failed))
(def TranslationJob
  [:map [:id :any] [:asset-id :any]
   ;; The job validates but deliberately retains the caller's tag spelling.
   [:target-language [:and :string [:fn #(boolean (shared/canonical-tag shared/target-languages %))]]]
   [:state JobState] [:transcript-id :any] [:subtitle-id :any]
   [:error [:maybe :keyword]]])
