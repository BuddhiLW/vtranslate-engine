(ns vtranslate.engine.calc.captions-trifecta-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.schema.calc-reflow :as s]
            [vtranslate.engine.calc.captions :as c]
            [vtranslate.engine.calc.caption-layout :as l]))

(def style-gen
  (gen/one-of [(gen/return nil) (gen/return {:font-family nil :wrap nil})
               (gen/hash-map :size-pct (gen/choose 1 20) :min-size-px (gen/choose 1 30)
                             :bold? gen/boolean :wrap (gen/choose 1 80)
                             :y-position (gen/elements [-1 0 0.5 0.94 1 2])
                             :plate-opacity (gen/elements [-2 0 0.5 1 3]))]))
(def height-gen (gen/choose 1 2160))
(def text-gen (gen/elements ["" "hello world" "WWW iii" "café 字幕" "😀" "one two three four five"]))
(def lines-gen (gen/vector text-gen 0 5))

(deftrifecta style c/style
  {:golden-path "test/golden/calc-reflow-caption-style.edn" :apply? true
   :cases {:default [nil] :empty [{}] :nils [{:wrap nil :font-family nil}]
           :partial [{:bold? false :wrap 20}]}
   :gen (gen/tuple style-gen) :pred #(m/validate s/Style %)
   :mutations [["nil-overwrites" #(merge c/defaults %)]]})

(deftrifecta font-size-px c/font-size-px
  {:golden-path "test/golden/calc-reflow-font-size.edn" :apply? true
   :cases {:floor [100 nil] :fraction [1080 {}] :explicit [100 {:size-px 50}]
           :nonpositive-explicit [1080 {:size-px 0}]}
   :gen (gen/tuple height-gen style-gen) :pred #(m/validate s/Positive %)
   :mutations [["ignores-explicit" (fn [h st] (c/font-size-px h (dissoc st :size-px)))]]})

(deftrifecta block-bottom-px c/block-bottom-px
  {:golden-path "test/golden/calc-reflow-block-bottom.edn" :apply? true
   :cases {:default [1080 nil] :above [100 {:y-position -1}] :below [100 {:y-position 2}]
           :zero-height [0 nil]}
   :gen (gen/tuple height-gen style-gen) :pred #(m/validate s/Nat %)
   :mutations [["unclamped" (fn [h st] (long (* h (:y-position (c/style st)))))]]})

(deftrifecta baseline-px c/baseline-px
  {:golden-path "test/golden/calc-reflow-baseline.edn" :apply? true
   :cases {:single [1080 nil 1 40 0] :top [1080 nil 3 40 0]
           :bottom [1080 nil 3 40 2] :negative [10 nil 3 40 0]}
   :gen (gen/bind (gen/choose 1 5)
                  (fn [n] (gen/tuple height-gen style-gen (gen/return n)
                                     (gen/choose 1 100) (gen/choose 0 (dec n)))))
   :pred #(m/validate :int %)
   :mutations [["stacks-down" (fn [h st n lh i] (+ (c/block-bottom-px h st) (* lh i)))]]})

(deftrifecta plate-alpha c/plate-alpha
  {:golden-path "test/golden/calc-reflow-plate-alpha.edn" :apply? true
   :cases {:default [nil] :transparent [{:plate-opacity 0}] :opaque [{:plate-opacity 1}]
           :low [{:plate-opacity -1}] :high [{:plate-opacity 3}]}
   :gen (gen/tuple style-gen) :pred #(m/validate s/ColorByte %)
   :mutations [["always-opaque" (constantly 255)]]})

(deftrifecta rgb c/rgb
  {:golden-path "test/golden/calc-reflow-rgb.edn" :apply? true
   :cases {:default [nil] :short ["#fc0"] :long ["#12abEF"] :invalid ["#xyz"] :blank [""]}
   :gen (gen/tuple (gen/one-of [gen/string-alphanumeric
                               (gen/fmap #(format "#%06x" %) (gen/choose 0 16777215))]))
   :pred #(m/validate s/RGB %)
   :mutations [["always-white" (constantly [255 255 255])]]})

(deftrifecta colors c/colors
  {:golden-path "test/golden/calc-reflow-colors.edn" :apply? true
   :cases {:default [nil] :custom [{:text-color "#fc0" :outline-color "invalid" :plate-color "#123456"}]}
   :gen (gen/tuple (gen/hash-map :text-color (gen/elements [nil "#fc0" "bad!"])
                                 :plate-color (gen/elements ["#123456" "#fff"])))
   :pred #(m/validate s/Colors %)})

(deftrifecta max-line-px l/max-line-px
  {:golden-path "test/golden/calc-reflow-max-line-px.edn" :apply? true
   :cases {:zero [0] :tiny [1] :landscape [1920] :portrait [360]}
   :gen (gen/tuple (gen/choose 0 7680)) :pred #(m/validate s/Positive %)
   :mutations [["no-margin" identity]]})

;; A function is not EDN. Observe the returned pure measure on a fixed probe
;; vector; generated styles still exercise the actual factory on every trial.
(defn- measure-probes [style]
  (let [measure (l/approximate-measure style)]
    (mapv #(measure 20 %) ["" "Wi" "café" "字幕" "😀" "\n"])))
(deftrifecta approximate-measure measure-probes
  {:golden-path "test/golden/calc-reflow-approximate-measure.edn" :apply? true
   :cases {:default [nil] :regular [{:bold? false}] :mono [{:font-family "Mono"}]}
   :gen (gen/tuple style-gen)
   :pred #(and (m/validate [:vector [:double {:min 0}]] %) (zero? (first %)))
   :mutations [["zero-width" (constantly [0.0 0.0 0.0 0.0 0.0 0.0])]]})

;; Pass stable pure measurements at the boundary, never serialize a closure.
(defn- wrap-count [text chars px]
  (l/wrap-text text chars px count))
(deftrifecta wrap-text wrap-count
  {:golden-path "test/golden/calc-reflow-wrap-text.edn" :apply? true
   :cases {:empty ["" 42 100] :nil [nil 42 100] :fits [" hello " 42 100]
           :wrap ["one two three four" 7 7] :long-word ["extraordinary" 3 3]
           :disabled ["one two" nil 100] :nonpositive ["one two" 0 100]}
   :gen (gen/tuple text-gen (gen/one-of [(gen/return nil) (gen/choose -1 42)]) (gen/choose 1 100))
   :pred #(m/validate s/Lines %)
   :mutations [["never-wraps" (fn [text _ _] [(str text)])]]})

(defn- measured-layout [frame style lines]
  (l/layout frame style lines (l/approximate-measure style)))
(deftrifecta layout measured-layout
  {:golden-path "test/golden/calc-reflow-caption-layout.edn" :apply? true
   :cases {:empty [{:width 1920 :height 1080} nil []]
           :normal [{:width 1920 :height 1080} nil ["hello world"]]
           :shrink [{:width 100 :height 1080} nil ["WWWWWWWWWWWWWWWWWWWWWWWW"]]
           :nil-line [{:width 360 :height 640} nil [nil "hello"]]}
   :gen (gen/tuple (gen/hash-map :width (gen/choose 1 1920) :height height-gen) style-gen lines-gen)
   :pred #(and (m/validate s/Layout %)
               (or (= 1 (:font-size-px %)) (<= (:widest-px %) (:max-line-px %))))})

(deftest public-caption-callables-accounted-for
  (is (= '#{style font-size-px block-bottom-px baseline-px plate-alpha rgb colors}
         (set (for [[k v] (ns-publics 'vtranslate.engine.calc.captions)
                    :when (:arglists (meta v))] k))))
  (is (= '#{max-line-px approximate-measure wrap-text layout}
         (set (for [[k v] (ns-publics 'vtranslate.engine.calc.caption-layout)
                    :when (:arglists (meta v))] k)))))
