(ns vtranslate.engine.schema.calc-text
  "Non-runtime contracts for the six text calc seams. No global registry changes."
  (:require [malli.core :as m]
            [vtranslate.engine.schema :as s]
            [vtranslate.engine.calc.ass :as ass]
            [vtranslate.engine.calc.subtitle-in :as si]
            [vtranslate.engine.calc.subtitle-out :as so]
            [vtranslate.engine.calc.transcription :as tx]
            [vtranslate.engine.calc.translation :as tr]
            [vtranslate.engine.calc.word-cues :as wc]))

(def Strings [:vector :string])
(def Clock [:re #"\d+:\d{2}:\d{2}\.\d{2}"])
(def Colour [:re #"&H[0-9A-F]{8}"])
(def Pixels [:int {:min 0}])
(def Stroke [:int {:min 1}])
(def Frame [:map [:width pos-int?] [:height pos-int?]])
(def Overlay [:map [:start-ms :int] [:end-ms :int] [:lines Strings]])
(def CueData [:map [:start-ms :int] [:end-ms :int] [:lines Strings]])
(def Cues [:vector CueData])
(def Status [:map [:adt/variant :qualified-keyword]])
(def Transcript
  [:map [:id :any] [:asset-id :any] [:language s/Language]
   [:segments [:vector s/Segment]] [:status Status]])
(def Track
  [:map [:id :any] [:source-id :any] [:language s/Language]
   [:format Status] [:cues [:vector s/Cue]] [:status Status]])
(def Unit
  [:map [:range s/TimeRange] [:source-language [:maybe s/Language]]
   [:source-text :string] [:target-text :string]])
(def Translated
  [:map [:id :any] [:transcript-id :any] [:source-language s/Language]
   [:target-language s/Language] [:units [:vector Unit]] [:status Status]])
(def IndexedTexts [:vector [:tuple :int :string]])
(def Outcomes
  [:map
   [:delivered [:vector [:map [:target-language :string] [:translated :any]]]]
   [:failed [:vector [:map [:target-language :string] [:error :qualified-keyword]]]]])
(def SegmentSeconds [:map [:start number?] [:end number?] [:text :string]])
(def Word [:map [:start number?] [:end number?] [:word :string]])
(def Policy [:map [:max-ms {:optional true} pos-int?]
             [:max-chars {:optional true} pos-int?]
             [:pause-ms {:optional true} pos-int?]
             [:min-ms {:optional true} Pixels]])

(def SegmentData
  [:map [:start-ms :int] [:end-ms :int] [:text :string]
   [:confidence number?] [:language {:optional true} [:maybe :string]]])

(def TranscriptRequest
  [:map [:id {:optional true} :any] [:asset-id {:optional true} :any]
   [:language [:maybe :string]] [:segments [:sequential SegmentData]]])

(def TrackSpec
  [:map [:id {:optional true} :any] [:source-id {:optional true} :any]
   [:language :string] [:format :qualified-keyword]])

(def TranslationSpec [:map [:id {:optional true} :any] [:target-language :string]])

(def Targets
  [:map [:target-language {:optional true} [:maybe :string]]
   [:target-languages {:optional true} [:maybe [:sequential [:maybe :string]]]]])

(def TranslationError
  [:map [:error [:= :error/translation-failed]] [:reason :string]])

(m/=> vtranslate.engine.calc.ass/timestamp [:=> [:cat :int] Clock])
(m/=> vtranslate.engine.calc.ass/colour [:=> [:cat [:tuple :int :int :int] :int] Colour])
(m/=> vtranslate.engine.calc.ass/font-name [:=> [:cat [:maybe :string]] :string])
(m/=> vtranslate.engine.calc.ass/escape-text [:=> [:cat [:maybe :string]] :string])
(m/=> vtranslate.engine.calc.ass/side-margin-px [:=> [:cat Pixels] Pixels])
(m/=> vtranslate.engine.calc.ass/stroke-px [:=> [:cat Pixels] Stroke])
(m/=> vtranslate.engine.calc.ass/style-row [:=> [:cat pos-int? pos-int? [:maybe :map]] :string])
(m/=> vtranslate.engine.calc.ass/dialogue-line [:=> [:cat Overlay [:maybe pos-int?] [:maybe ifn?]] [:maybe :string]])
(m/=> vtranslate.engine.calc.ass/document [:function
                    [:=> [:cat Frame [:maybe :map] [:sequential Overlay]] :string]
                    [:=> [:cat Frame [:maybe :map] [:sequential Overlay] ifn?] :string]])
(m/=> vtranslate.engine.calc.subtitle-in/build-subtitle-track [:=> [:cat [:sequential CueData] TrackSpec] (s/result-of Track)])
(m/=> vtranslate.engine.calc.subtitle-out/cue-texts [:=> [:cat [:sequential CueData]] Strings])
(m/=> vtranslate.engine.calc.subtitle-out/apply-translations [:=> [:cat [:sequential CueData] [:sequential :string]] (s/result-of Cues)])
(m/=> vtranslate.engine.calc.transcription/detected-language [:=> [:cat [:sequential :map]] [:maybe s/Language]])
(m/=> vtranslate.engine.calc.transcription/build-transcript [:=> [:cat TranscriptRequest] (s/result-of Transcript)])
(m/=> vtranslate.engine.calc.translation/build-translated-cues [:=> [:cat Transcript Strings TranslationSpec] (s/result-of Translated)])
(m/=> vtranslate.engine.calc.translation/normalize-targets [:=> [:cat Targets] Strings])
(m/=> vtranslate.engine.calc.translation/segment-source-language [:=> [:cat :map [:maybe :string] :map] :string])
(m/=> vtranslate.engine.calc.translation/translation-group [:=> [:cat :map [:maybe :string] :string :map] [:or :string :qualified-keyword]])
(m/=> vtranslate.engine.calc.translation/verbatim-translations [:=> [:cat [:sequential [:tuple :int :map]]] (s/result-of IndexedTexts)])
(m/=> vtranslate.engine.calc.translation/translation-count-error [:=> [:cat :any Pixels Pixels] TranslationError])
(m/=> vtranslate.engine.calc.translation/target-outcomes [:=> [:cat Strings [:sequential :any] :any] Outcomes])
(m/=> vtranslate.engine.calc.word-cues/recut [:function
                [:=> [:cat [:sequential SegmentSeconds] [:sequential Word]] [:vector SegmentSeconds]]
                [:=> [:cat [:sequential SegmentSeconds] [:sequential Word] [:maybe Policy]] [:vector SegmentSeconds]]])
