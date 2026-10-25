(ns vtranslate.engine.calc.rendering-trifecta-test
  "Golden decisions, generated output contracts, and killed counterexamples."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.schema.calc-render :as s]
            [vtranslate.engine.calc.rendering :as sut]
            [clojure.walk :as walk]))

(def ^:private text-gen
  (gen/one-of [gen/string-alphanumeric
               (gen/elements ["" "a b c" "a:b,c;[x]'\\path" "Olá 世界"])]))
(def ^:private dimension-gen (gen/choose 2 7680))
(def ^:private quality-gen (gen/elements [:source :360p :720p :1080p :2160p nil]))
(def ^:private encoder-gen (gen/elements [:libx264 :h264-nvenc :h264-vaapi]))
(def ^:private plan
  {:width 1280 :height 720 :video-bitrate 4000000 :audio-bitrate 128000 :rescaled? false})
(def ^:private plan-gen
  (gen/fmap #(assoc plan :rescaled? %) gen/boolean))

(defn- translated [texts]
  {:id "translated" :target-language "es"
   :units (mapv (fn [i text]
                  {:range {:start {:ms (* i 1000)} :end {:ms (* (inc i) 1000)}}
                   :target-text text}) (range) texts)})

(deftrifecta build-subtitle-track-contract sut/build-subtitle-track
  {:golden-path "test/golden/calc-render-rendering-build-subtitle-track.edn"
   :xf #(walk/postwalk (fn [x] (if (record? x) (into {} x) x)) %)
   :apply? true
   :cases {:valid [(translated ["Hola" "mundo"]) {:id "s" :format :format/srt}] :empty [(translated []) {:id "s" :format :format/srt}] :invalid-format [(translated ["x"]) {:id "s" :format :format/nope}] :omitted [(update (translated ["x" "y"]) :units #(assoc-in % [0 :segment/omit?] true)) {:id "s" :format :format/vtt}] :reflow [(translated ["one two three four"]) {:id "s" :format :format/srt :reflow {:max-chars 10}}]}
   :gen (gen/tuple (gen/fmap translated (gen/vector (gen/fmap #(str "text" %) gen/string-alphanumeric) 0 8)) (gen/elements [{:id "s" :format :format/srt} {:id "s" :format :format/vtt}] ))
   :pred #(m/validate s/TrackResult %)
   :mutations [["drops-result" (constantly ::dropped)]]})
