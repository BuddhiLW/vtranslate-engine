(ns vtranslate.engine.calc.ass-trifecta-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.schema.calc-text :as s]
            [vtranslate.engine.calc.ass :as sut]))

(deftrifecta timestamp sut/timestamp
  {:golden-path "test/golden/calc-text-ass-timestamp.edn" :apply? true
   :cases {:negative [-1] :zero [0] :rollover [3600019] :truncate [999]}
   :gen (gen/tuple (gen/choose -100000 100000000))
   :pred #(m/validate s/Clock %)
   :mutations [["constant-clock" (constantly "0:00:00.00")]]})
(deftrifecta colour sut/colour
  {:golden-path "test/golden/calc-text-ass-colour.edn" :apply? true
   :cases {:red [[255 0 0] 0] :clamp [[-1 300 42] 999]}
   :gen (gen/tuple (gen/tuple gen/int gen/int gen/int) gen/int)
   :pred #(m/validate s/Colour %)
   :mutations [["black" (constantly "&H00000000")]]})
(deftrifecta font-name sut/font-name
  {:golden-path "test/golden/calc-text-ass-font-name.edn" :apply? true
   :cases {:default [nil] :logical ["Monospaced"] :custom ["Noto Sans"] :dialog ["Dialog"]}
   :gen (gen/tuple (gen/one-of [gen/string-alphanumeric (gen/elements [nil "Serif" "SansSerif" "DialogInput" "Mono"])]))
   :pred #(and (m/validate :string %) (not (str/blank? %)))
   :mutations [["always-sans" (constantly "sans-serif")]]})
(deftrifecta escape-text sut/escape-text
  {:golden-path "test/golden/calc-text-ass-escape-text.edn" :apply? true
   :cases {:nil [nil] :override ["{\\fs99}a\r\nb"] :slash ["a\\b"]}
   :gen (gen/tuple (gen/fmap #(apply str %) (gen/vector (gen/elements ["a" "é" "{" "}" "\\" "\n" "\r\n"]) 0 30)))
   :pred #(and (m/validate :string %) (not (re-find #"[{}\n]" %)))
   :mutations [["unescaped" str]]})
(deftrifecta side-margin-px sut/side-margin-px
  {:golden-path "test/golden/calc-text-ass-side-margin.edn" :apply? true
   :cases {:zero [0] :hd [1920] :round [13]}
   :gen (gen/tuple (gen/choose 0 10000)) :pred #(m/validate s/Pixels %)
   :mutations [["no-margin" (constantly 0)]]})
(deftrifecta stroke-px sut/stroke-px
  {:golden-path "test/golden/calc-text-ass-stroke.edn" :apply? true
   :cases {:floor [0] :boundary [28] :large [140]}
   :gen (gen/tuple (gen/choose 0 1000)) :pred #(m/validate s/Stroke %)
   :mutations [["no-stroke" (constantly 0)]]})
(def style-gen
  (gen/hash-map :bold? gen/boolean :size-pct (gen/choose 1 20)
                :plate-opacity (gen/elements [0 0.5 1]) :y-position (gen/elements [0.1 0.8 1])))
(def cue-gen (gen/hash-map :start-ms (gen/choose 0 1000) :end-ms (gen/choose 1001 5000)
                           :lines (gen/vector gen/string-alphanumeric 0 4)))
(deftrifecta style-row sut/style-row
  {:golden-path "test/golden/calc-text-ass-style.edn" :apply? true
   :cases {:default [1920 1080 nil] :no-plate [640 360 {:plate-opacity 0}] :bold [1080 1920 {:bold? true}]}
   :gen (gen/tuple (gen/choose 100 4000) (gen/choose 100 4000) style-gen)
   :pred #(and (m/validate :string %) (str/starts-with? % "Style: Default,") (= 23 (count (str/split % #","))))
   :mutations [["missing-style" (constantly "")]]})
(deftrifecta dialogue-line sut/dialogue-line
  {:golden-path "test/golden/calc-text-ass-dialogue.edn" :apply? true
   :cases {:visible [{:start-ms 0 :end-ms 1500 :lines ["hello" "world"]} 42 nil]
           :empty [{:start-ms 0 :end-ms 1 :lines [""]} 42 nil]
           :reversed [{:start-ms 2 :end-ms 1 :lines ["x"]} 42 nil]
           :resize [{:start-ms 0 :end-ms 1 :lines ["x"]} 42 (fn [_] {:lines ["x"] :font-size-px 20})]}
   :gen (gen/tuple cue-gen (gen/choose 18 80) (gen/elements [nil (fn [lines] {:lines lines :font-size-px 20})]))
   :pred #(and (m/validate [:maybe :string] %) (or (nil? %) (str/starts-with? % "Dialogue: 0,")))
   :mutations [["drop-all" (constantly nil)]]})
(deftrifecta document sut/document
  {:golden-path "test/golden/calc-text-ass-document.edn" :apply? true
   :cases {:empty [{:width 640 :height 360} nil []]
           :cue [{:width 1920 :height 1080} {} [{:start-ms 0 :end-ms 2000 :lines ["Hello"]}]]
           :measured [{:width 640 :height 360} {} [{:start-ms 0 :end-ms 2000 :lines ["a b"]}] (fn [size text] (* (count text) size))]}
   :gen (gen/one-of [(gen/tuple (gen/hash-map :width (gen/choose 300 2000) :height (gen/choose 300 2000)) style-gen (gen/vector cue-gen 0 5))
                    (gen/tuple (gen/return {:width 640 :height 360}) style-gen (gen/vector cue-gen 0 5) (gen/return (fn [size text] (* (count text) size))))])
   :pred #(and (m/validate :string %) (str/includes? % "[Events]") (str/includes? % "[V4+ Styles]") (str/ends-with? % "\n"))
   :mutations [["empty-script" (constantly "")]]})
