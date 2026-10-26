(ns vtranslate.engine.calc.encoding-trifecta-test
  "Golden decisions, generated output contracts, and killed counterexamples."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.schema.calc-render :as s]
            [vtranslate.engine.calc.encoding :as sut]))

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

(deftrifecta target-dimensions-contract sut/target-dimensions
  {:golden-path "test/golden/calc-render-encoding-target-dimensions.edn"
   :apply? true
   :cases {:source [1921 1081 :source] :downscale [1920 1080 :720p] :no-upscale [640 360 :2160p] :zero [0 0 nil]}
   :gen (gen/tuple dimension-gen dimension-gen quality-gen)
   :pred #(m/validate s/Dimensions %)
   :mutations [["drops-result" (constantly ::dropped)]]})

(deftrifecta video-bitrate-contract sut/video-bitrate
  {:golden-path "test/golden/calc-render-encoding-video-bitrate.edn"
   :apply? true
   :cases {:missing [{}] :source [{:source-bitrate 6000000 :width 1920 :height 1080}] :scaled [{:source-bitrate 6000000 :source-width 1920 :source-height 1080 :width 960 :height 540}]}
   :gen (gen/tuple (gen/fmap (fn [[w h rate]] {:width w :height h :source-width 3840 :source-height 2160 :source-bitrate rate}) (gen/tuple dimension-gen dimension-gen (gen/choose 0 20000000))))
   :pred #(m/validate [:int {:min 400000}] %)
   :mutations [["drops-result" (constantly ::dropped)]]})

(deftrifecta audio-bitrate-contract sut/audio-bitrate
  {:golden-path "test/golden/calc-render-encoding-audio-bitrate.edn"
   :apply? true
   :cases {:missing [nil] :zero [0] :negative [-1] :source [256000]}
   :gen (gen/tuple (gen/one-of [(gen/return nil) (gen/choose -100 1000000)]))
   :pred #(m/validate s/Positive %)
   :mutations [["drops-result" (constantly ::dropped)]]})

(deftrifecta encoder-threads-contract sut/encoder-threads
  {:golden-path "test/golden/calc-render-encoding-encoder-threads.edn"
   :apply? true
   :cases {:missing [nil] :negative [-5] :one [1] :capped [128]}
   :gen (gen/tuple (gen/one-of [(gen/return nil) (gen/choose -100 1000)]))
   :pred #(m/validate [:int {:min 1 :max 16}] %)
   :mutations [["drops-result" (constantly ::dropped)]]})

(deftrifecta plan-contract sut/plan
  {:golden-path "test/golden/calc-render-encoding-plan.edn"
   :apply? true
   :cases {:source [{:source-width 1921 :source-height 1081}] :scaled [{:source-width 3840 :source-height 2160 :quality :720p}]}
   :gen (gen/tuple (gen/fmap (fn [[w h q]] {:source-width w :source-height h :quality q}) (gen/tuple dimension-gen dimension-gen quality-gen)))
   :pred #(m/validate s/Plan %)
   :mutations [["drops-result" (constantly ::dropped)]]})
