(ns vtranslate.engine.calc.watermark-trifecta-test
  "Golden decisions, generated output contracts, and killed counterexamples."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.schema.calc-render :as s]
            [vtranslate.engine.calc.watermark :as w]))

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

(deftrifecta geometry-contract w/geometry
  {:golden-path "test/golden/calc-render-watermark-geometry.edn"
   :apply? true
   :cases {:small [1] :hd [1080] :uhd [2160] :custom [720 {:size-pct 0.1 :margin-pct 0.05}]}
   :gen (gen/tuple dimension-gen (gen/fmap (fn [[s m]] {:size-pct (/ s 100.0) :margin-pct (/ m 100.0)}) (gen/tuple (gen/choose 1 20) (gen/choose 0 10))))
   :pred #(m/validate s/Geometry %)
   :mutations [["drops-result" (constantly ::dropped)]]})

(deftrifecta glyph-polygon-contract w/glyph-polygon
  {:golden-path "test/golden/calc-render-watermark-glyph-polygon.edn"
   :apply? true
   :cases {:zero [0] :tile [32] :large [128]}
   :gen (gen/tuple (gen/choose 0 4096))
   :pred #(m/validate s/Polygon %)
   :mutations [["drops-result" (constantly ::dropped)]]})

(deftrifecta overlay-position-contract w/overlay-position
  {:golden-path "test/golden/calc-render-watermark-overlay-position.edn"
   :apply? true
   :cases {:small [{:size 22 :margin 12 :corner 6}] :large [{:size 100 :margin 60 :corner 28}]}
   :gen (gen/tuple (gen/fmap w/geometry dimension-gen))
   :pred #(m/validate [:tuple :string :string] %)
   :mutations [["drops-result" (constantly ::dropped)]]})
