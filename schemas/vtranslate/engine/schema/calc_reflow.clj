(ns vtranslate.engine.schema.calc-reflow
  "Opt-in contracts for pure reflow and caption calculations. No runtime dependency.
   Cue ends may equal starts: gap enforcement can collapse a cue to zero duration."
  (:require [malli.core :as m]
            [vtranslate.engine.calc.reflow :as reflow]
            [vtranslate.engine.calc.reflow.linebreak :as linebreak]
            [vtranslate.engine.calc.reflow.optimal :as optimal]
            [vtranslate.engine.calc.caption-layout :as layout]
            [vtranslate.engine.calc.captions :as captions]))

(def Nat [:int {:min 0}])
(def Positive [:int {:min 1}])
(def Lines [:vector :string])
(def Cue [:and [:map [:index Nat] [:start-ms Nat] [:end-ms Nat] [:lines Lines]]
          [:fn #(<= (:start-ms %) (:end-ms %))]])
(def Cues [:vector Cue])
(def Rules
  [:map
   [:strategy {:optional true} [:enum :greedy :optimal "greedy" "optimal"]]
   [:drop-music? {:optional true} :boolean]
   [:max-chars-line {:optional true} Positive]
   [:max-lines {:optional true} Positive]
   [:max-cps {:optional true} Positive]
   [:snap {:optional true} Positive]
   [:max-dur-ms {:optional true} Positive]
   [:min-dur-ms {:optional true} Nat]
   [:min-gap-ms {:optional true} Nat]])
(def Style [:map [:font-family :string] [:bold? :boolean] [:size-pct number?] [:min-size-px Positive] [:y-position number?] [:wrap Positive] [:text-color :string] [:outline-color :string] [:plate-color :string] [:plate-opacity number?]])
(def Requested [:maybe [:map]])
(def ColorByte [:int {:min 0 :max 255}])
(def RGB [:tuple ColorByte ColorByte ColorByte])
(def Colors [:map [:text RGB] [:outline RGB] [:plate RGB]])
(def Breaks [:map [:lines Lines] [:cost number?] [:overflow? :boolean]])
(def Solution [:map [:cues Cues] [:cost number?]])
(def Layout [:map [:font-size-px Positive] [:lines Lines]
             [:widest-px Nat] [:max-line-px Positive]])
(def Result [:or [:map [:ok Cues]]
             [:map [:error [:= :error/render-failed]] [:reason :string]]])
(def Strategies [:set :keyword])
(def WeakWords [:set :string])

(m/=> vtranslate.engine.calc.reflow/words [:=> [:cat [:sequential :string]] Lines])
(m/=> vtranslate.engine.calc.reflow/drop-music [:=> [:cat Cues] Cues])
(m/=> vtranslate.engine.calc.reflow/merge-overlaps [:=> [:cat Cues] Cues])
(m/=> vtranslate.engine.calc.reflow/split-cues [:=> [:cat Positive [:maybe Positive] [:maybe Positive] Cues] Cues])
(m/=> vtranslate.engine.calc.reflow/snap-cues [:=> [:cat Positive Cues] Cues])
(m/=> vtranslate.engine.calc.reflow/reindex [:=> [:cat Cues] Cues])
(m/=> vtranslate.engine.calc.reflow/shape [:=> [:cat Cues Rules] Cues])
(m/=> vtranslate.engine.calc.reflow/strategies [:=> [:cat] Strategies])
(m/=> vtranslate.engine.calc.reflow/reflow [:=> [:cat Cues Rules] Cues])
(m/=> vtranslate.engine.calc.reflow/try-reflow [:=> [:cat Cues [:map]] Result])
(m/=> vtranslate.engine.calc.reflow.linebreak/weak-set [:=> [:cat [:maybe :string]] WeakWords])
(m/=> vtranslate.engine.calc.reflow.linebreak/break-lines [:=> [:cat Lines Positive [:map]] Breaks])
(m/=> vtranslate.engine.calc.reflow.optimal/solve [:=> [:cat Cues Rules] Solution])
(m/=> vtranslate.engine.calc.caption-layout/max-line-px [:=> [:cat Nat] Positive])
(m/=> vtranslate.engine.calc.caption-layout/approximate-measure [:=> [:cat Requested] ifn?])
(m/=> vtranslate.engine.calc.caption-layout/wrap-text [:=> [:cat [:maybe :string] [:maybe :int] number? ifn?] Lines])
(m/=> vtranslate.engine.calc.caption-layout/layout [:=> [:cat [:map [:width Positive] [:height Positive]] Requested Lines ifn?] Layout])
(m/=> vtranslate.engine.calc.captions/style [:=> [:cat Requested] Style])
(m/=> vtranslate.engine.calc.captions/font-size-px [:=> [:cat Positive Requested] Positive])
(m/=> vtranslate.engine.calc.captions/block-bottom-px [:=> [:cat Nat Requested] Nat])
(m/=> vtranslate.engine.calc.captions/baseline-px [:=> [:cat Nat Requested Positive Positive Nat] :int])
(m/=> vtranslate.engine.calc.captions/plate-alpha [:=> [:cat Requested] ColorByte])
(m/=> vtranslate.engine.calc.captions/rgb [:=> [:cat :any] RGB])
(m/=> vtranslate.engine.calc.captions/colors [:=> [:cat Requested] Colors])
