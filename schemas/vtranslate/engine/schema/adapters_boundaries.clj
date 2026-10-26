(ns vtranslate.engine.schema.adapters-boundaries
  "Wire values and pure conversion contracts; never required by src."
  (:require [malli.core :as m]
            [vtranslate.engine.schema :as s]
            [vtranslate.engine.schema.port-values :as p]
            [vtranslate.engine.adapters.codec.timecode :as tc]
            [vtranslate.engine.adapters.codec.block :as block]
            [vtranslate.engine.adapters.transcriber.support :as sup]
            [vtranslate.engine.adapters.segmenter.stub :as grid]
            [vtranslate.engine.adapters.transcriber.stub :as stub]))

(def CliArgs [:vector :string])
(def ProviderSegment
  [:map [:start :number] [:end :number] [:text :string]
   [:confidence {:optional true} :number]])
(def VerboseReply
  [:map [:text {:optional true} :string]
   [:language {:optional true} :string]
   [:segments {:optional true} [:vector ProviderSegment]]
   [:words {:optional true} [:vector [:map [:start :number] [:end :number] [:word :string]]]]])
(def ChatReply
  [:map [:choices [:vector [:map [:message [:map [:content :string]]]]]]])
(def MultipartParts
  [:vector [:map [:part-name :string] [:content :any]
            [:file-name {:optional true} :string]
            [:mime-type {:optional true} :string]]])

(m/=> tc/ms->clock [:=> [:cat s/Nat [:enum "," "."]] :string])
(m/=> tc/clock->ms [:=> [:cat :string] [:maybe s/Nat]])
(m/=> block/cue->block [:=> [:cat [:enum "," "."] s/Cue] :string])
(m/=> block/split-blocks [:=> [:cat :string] [:vector :string]])
(m/=> sup/audio->path [:=> [:cat :any] [:maybe :string]])
(m/=> sup/normalize-segments
      [:function [:=> [:cat [:sequential :map]] p/Segments]
       [:=> [:cat [:sequential :map] :map] p/Segments]])
(m/=> sup/offset-segments [:=> [:cat :int [:sequential :map]] [:vector :map]])
(m/=> sup/clamp-to-window [:=> [:cat :int :int [:sequential :map]] [:vector :map]])
(m/=> sup/resolve-decode-opts [:=> [:cat :map] :map])
(m/=> grid/grid-spans [:=> [:cat :int :int] p/Spans])
(m/=> stub/stub-segments [:=> [:cat :int :int [:maybe :string]] p/Segments])
