(ns vtranslate.engine.schema.native-media
  "Non-runtime contracts for the bytedeco (:ffmpeg) media adapters and the
   observations the native suite (test-native/) makes of their output files.
   Never required by src; collect.ffmpeg and the composers stay malli-free.

   Container facts are PROJECTED to plain EDN before they reach a schema: codec
   names, stream types and packet counts, never native pointers or raw bytes."
  (:require [vtranslate.engine.schema :as s]
            [vtranslate.engine.schema.port-values :as pv]))

(def MediaProbe
  "collect.ffmpeg JavaCvMedia/probe of a file, durations already in ms."
  [:map
   [:container :string]
   [:duration-ms s/Nat]
   [:has-audio? :boolean]
   [:has-video? :boolean]
   [:audio-codec [:maybe :string]]
   [:sample-rate s/Nat]
   [:channels s/Nat]])

(def AvMediaType
  "libavutil AVMediaType ordinals that occur here: video 0, audio 1, subtitle 3."
  [:enum 0 1 3])

(def StreamInfo
  [:map [:type AvMediaType] [:codec :string] [:language [:maybe :string]]])

(def MuxedSubtitle
  "One mov_text sample read back from a container: pts window in ms + text
   (the uint16 length prefix stripped). Gaps between cues come back as samples
   with empty text, written by the mp4 muxer itself."
  [:and [:map [:start-ms s/Nat] [:end-ms s/Nat] [:text :string]]
   [:fn #(<= (:start-ms %) (:end-ms %))]])

(def ContainerReport
  [:map
   [:streams [:vector StreamInfo]]
   [:packets [:map-of s/Nat pos-int?]]
   [:subtitles [:vector MuxedSubtitle]]])

(def FrameObservation
  "One decoded output frame against the source frame at the same timestamp:
   whether a cue was active (the oracle) and whether the picture changed beyond
   re-encode noise (the observation)."
  [:map [:t-ms s/Nat] [:cue-active? :boolean] [:changed? :boolean]])

(def CueSpec
  "Plain cue data a generated subtitle track is built from."
  [:and [:map [:start-ms s/Nat] [:end-ms s/Nat]
         [:lines [:vector {:min 1} [:string {:min 1}]]]]
   [:fn #(< (:start-ms %) (:end-ms %))]])

(def CueSpecs [:and [:vector {:min 1} CueSpec] [:fn pv/ordered?]])

(def BurnObservation
  "IHardsubBurner/burn! over a generated source: the returned path, the probe
   of the written file, and the per-frame comparison."
  [:map
   [:result pv/BurnValue]
   [:probe MediaProbe]
   [:frames [:vector {:min 1} FrameObservation]]])

(def HardsubObservation
  "HardsubComposer built by make-composer then composed over a generated
   source. :frames is absent when wiring or composing refused."
  [:map
   [:make [:or [:map [:ok [:map [:backend :keyword]]]] [:map [:error :keyword]]]]
   [:result {:optional true} pv/ComposeResult]
   [:partials {:optional true} [:vector :string]]
   [:frames {:optional true} [:vector FrameObservation]]])

(def SoftMuxObservation
  [:map
   [:result pv/ComposeResult]
   [:source ContainerReport]
   [:output ContainerReport]
   [:partials [:vector :string]]])
