(ns vtranslate.engine.calc.overlay-trifecta-test
  "Golden decisions, generated output contracts, and killed counterexamples."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.schema.calc-render :as s]
            [vtranslate.engine.calc.overlay :as sut]))

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

(defn- cue [start end text]
  {:index 1 :range {:start {:ms start} :end {:ms end}} :lines [text]})

(deftrifecta timeline-contract sut/timeline
  {:golden-path "test/golden/calc-render-overlay-timeline.edn"
   :apply? true
   :cases {:empty [{:cues []}] :unsorted [{:cues [(cue 100 200 "later") (cue 0 100 "first")]}]}
   :gen (gen/tuple (gen/fmap (fn [starts] {:cues (mapv #(cue % (+ % 100) "line") starts)}) (gen/vector (gen/choose 0 100000) 0 12)))
   :pred #(m/validate s/Timeline %)
   :mutations [["drops-result" (constantly ::dropped)]]})

(deftrifecta active-lines-contract sut/active-lines
  {:golden-path "test/golden/calc-render-overlay-active-lines.edn"
   :apply? true
   :cases {:start [[{:start-ms 0 :end-ms 10 :lines ["x"]}] 0] :end [[{:start-ms 0 :end-ms 10 :lines ["x"]}] 10] :empty [[] 0]}
   :gen (gen/tuple (gen/return [{:start-ms 0 :end-ms 100 :lines ["x"]}]) (gen/choose -10 110))
   :pred #(m/validate [:maybe s/Argv] %)
   :mutations [["drops-result" (constantly ::dropped)]]})

(deftrifecta wrap-line-contract sut/wrap-line
  {:golden-path "test/golden/calc-render-overlay-wrap-line.edn"
   :apply? true
   :cases {:empty ["" 10] :disabled ["one two" nil] :wrapped ["one two three" 7] :long-word ["unbreakable" 3]}
   :gen (gen/tuple text-gen (gen/one-of [(gen/return nil) (gen/choose -2 30)]))
   :pred #(m/validate s/Argv %)
   :mutations [["drops-result" (constantly ::dropped)]]})
