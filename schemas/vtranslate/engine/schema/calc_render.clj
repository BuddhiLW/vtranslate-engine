(ns vtranslate.engine.schema.calc-render
  "Opt-in contracts for the pure burn/render calculations; no runtime dependency."
  (:require [malli.core :as m]
            [vtranslate.engine.schema :as s]
            [vtranslate.engine.calc.burn :as burn]
            [vtranslate.engine.calc.encoding :as encoding]
            [vtranslate.engine.calc.ffmpeg-args :as ffmpeg-args]
            [vtranslate.engine.calc.overlay :as overlay]
            [vtranslate.engine.calc.rendering :as rendering]
            [vtranslate.engine.calc.watermark :as watermark]))

(def Positive [:int {:min 1}])
(def EvenDimension [:and [:int {:min 0}] [:fn even?]])
(def Dimensions [:tuple EvenDimension EvenDimension])
(def Argv [:vector :string])
(def Plan [:map [:width EvenDimension] [:height EvenDimension]
           [:video-bitrate [:int {:min 400000}]] [:audio-bitrate Positive]
           [:rescaled? :boolean]])
(def EncoderSpec [:map [:codec :string]
                  [:hardware? {:optional true} :boolean]
                  [:default-preset {:optional true} :string]
                  [:pix-fmt {:optional true} :string]
                  [:filter-suffix {:optional true} :string]
                  [:device-args {:optional true} Argv]])
(def EncoderTable [:map-of :keyword EncoderSpec])
(def Capabilities [:map [:filters Argv] [:encoders Argv]])
(def VideoProbe [:maybe [:map [:width Positive] [:height Positive]
                        [:frame-rate [:maybe :double]] [:video-bitrate [:maybe :int]]]])
(def AudioProbe [:maybe [:map [:channels Positive] [:audio-bitrate [:maybe :int]]]])
(def OverlayCue [:map [:start-ms s/Nat] [:end-ms s/Nat] [:lines Argv]])
(def Timeline [:vector OverlayCue])
(def Geometry [:map [:size Positive] [:margin s/Nat] [:corner [:int {:min 2}]]])
(def Polygon [:vector {:min 7 :max 7} [:tuple :double :double]])
(def TrackResult (s/result-of
                  [:map [:id :string] [:source-id :string] [:language s/Language]
                   [:cues [:vector s/Cue]] [:format :map] [:status :map]]))

(m/=> vtranslate.engine.calc.burn/binary [:=> [:cat [:maybe :map]] :string])
(m/=> vtranslate.engine.calc.burn/requested [:=> [:cat [:maybe :map] [:or [:sequential :keyword] [:set :keyword]]] :keyword])
(m/=> vtranslate.engine.calc.burn/choose [:=> [:cat [:maybe :map] [:or [:sequential :keyword] [:set :keyword]] :boolean] :keyword])
(m/=> vtranslate.engine.calc.encoding/target-dimensions [:=> [:cat :int :int [:maybe :keyword]] Dimensions])
(m/=> vtranslate.engine.calc.encoding/video-bitrate [:=> [:cat :map] [:int {:min 400000}]])
(m/=> vtranslate.engine.calc.encoding/audio-bitrate [:=> [:cat [:maybe :int]] Positive])
(m/=> vtranslate.engine.calc.encoding/encoder-threads [:=> [:cat [:maybe :int]] [:int {:min 1 :max 16}]])
(m/=> vtranslate.engine.calc.encoding/plan [:=> [:cat [:map [:source-width Positive] [:source-height Positive]]] Plan])
(m/=> vtranslate.engine.calc.ffmpeg-args/filter-escape [:=> [:cat [:maybe :string]] :string])
(m/=> vtranslate.engine.calc.ffmpeg-args/video-filter [:function [:=> [:cat :map :string] :string] [:=> [:cat :map :string [:maybe :string]] :string]])
(m/=> vtranslate.engine.calc.ffmpeg-args/watermark-graph [:function [:=> [:cat :map :string [:tuple :string :string]] :string] [:=> [:cat :map :string [:tuple :string :string] [:maybe :string]] :string]])
(m/=> vtranslate.engine.calc.ffmpeg-args/encoders-from [:=> [:cat [:maybe :map]] EncoderTable])
(m/=> vtranslate.engine.calc.ffmpeg-args/encoder-spec [:function [:=> [:cat :keyword] EncoderSpec] [:=> [:cat EncoderTable :keyword] EncoderSpec]])
(m/=> vtranslate.engine.calc.ffmpeg-args/encoder-preset [:=> [:cat :keyword [:maybe :map]] [:maybe :string]])
(m/=> vtranslate.engine.calc.ffmpeg-args/burn-args [:=> [:cat [:map [:plan Plan]]] Argv])
(m/=> vtranslate.engine.calc.ffmpeg-args/probe-args [:=> [:cat [:map [:kind [:enum :video :audio]]]] Argv])
(m/=> vtranslate.engine.calc.ffmpeg-args/parse-frame-rate [:=> [:cat [:maybe :string]] [:maybe :double]])
(m/=> vtranslate.engine.calc.ffmpeg-args/parse-video-probe [:=> [:cat [:maybe :string]] VideoProbe])
(m/=> vtranslate.engine.calc.ffmpeg-args/parse-audio-probe [:=> [:cat [:maybe :string]] AudioProbe])
(m/=> vtranslate.engine.calc.ffmpeg-args/listing-args [:=> [:cat [:map [:listing [:enum :filters :encoders]]]] Argv])
(m/=> vtranslate.engine.calc.ffmpeg-args/lists-name? [:=> [:cat [:maybe :string] :string] :boolean])
(m/=> vtranslate.engine.calc.ffmpeg-args/capabilities-for [:function [:=> [:cat :keyword] Capabilities] [:=> [:cat EncoderTable :keyword] Capabilities]])
(m/=> vtranslate.engine.calc.ffmpeg-args/capable? [:function [:=> [:cat [:maybe :map]] :boolean] [:=> [:cat [:maybe :map] :keyword] :boolean] [:=> [:cat [:maybe :map] EncoderTable :keyword] :boolean]])
(m/=> vtranslate.engine.calc.ffmpeg-args/encoder-probe-args [:=> [:cat [:map [:encoder :keyword]]] Argv])
(m/=> vtranslate.engine.calc.ffmpeg-args/hardware-unavailable? [:=> [:cat [:maybe :string]] :boolean])
(m/=> vtranslate.engine.calc.ffmpeg-args/probe-binary [:=> [:cat [:maybe :string]] :string])
(m/=> vtranslate.engine.calc.overlay/timeline [:=> [:cat [:map [:cues [:sequential s/Cue]]]] Timeline])
(m/=> vtranslate.engine.calc.overlay/active-lines [:=> [:cat Timeline :int] [:maybe Argv]])
(m/=> vtranslate.engine.calc.overlay/wrap-line [:=> [:cat :string [:maybe :int]] Argv])
(m/=> vtranslate.engine.calc.rendering/build-subtitle-track [:=> [:cat :map [:map [:id :string] [:format :keyword]]] TrackResult])
(m/=> vtranslate.engine.calc.watermark/geometry [:function [:=> [:cat :int] Geometry] [:=> [:cat :int :map] Geometry]])
(m/=> vtranslate.engine.calc.watermark/glyph-polygon [:=> [:cat :int] Polygon])
(m/=> vtranslate.engine.calc.watermark/overlay-position [:=> [:cat Geometry] [:tuple :string :string]])
