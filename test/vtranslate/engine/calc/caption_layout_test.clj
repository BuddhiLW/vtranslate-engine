(ns vtranslate.engine.calc.caption-layout-test
  "A burned caption fits its frame. Over a matrix of portrait and landscape
   frames, every line calc.caption-layout lays out measures at most the frame
   width minus the margins, by the java.awt metrics the burners draw with and
   by the pure approximation; landscape defaults keep their old breaks and
   size; the ASS script carries the laid-out breaks and size."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [vtranslate.engine.calc.ass :as ass]
            [vtranslate.engine.calc.caption-layout :as sut]
            [vtranslate.engine.calc.captions :as captions]
            [vtranslate.engine.calc.overlay :as overlay]
            [vtranslate.engine.collect.font-metrics :as font-metrics]))

(def frames
  "[width height]: portrait 1080p and 4K, landscape 720p, 1080p and 4K, plus
   square and ultra-wide and a small portrait under the size floor."
  [[1080 1920] [2160 3840] [1280 720] [1920 1080] [3840 2160]
   [1080 1080] [3440 1440] [360 640]])

(def card-caption
  "The caption the defect was measured with (kanban 20260901002421-41a56353)."
  "Ela ficou completamente chocada com aquela resposta inesperada do rapaz")

(def captions
  [[card-caption]
   ["Short"]
   ["Pneumonoultramicroscopicsilicovolcanoconiosis e Anticonstitucionalissimamente"]
   ["WWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWW"]
   ["彼女はその少年の予想外の答えに完全にショックを受けていた"]
   ["first line of two" "and a second, rather longer line of the same cue"]])

(def styles
  [{} {:wrap 120} {:size-pct 12} {:size-pct 12 :wrap 120 :bold? false}
   {:font-family "Monospaced"} {:size-px 200}])

(defn- worst-overflow
  "Rows [frame style lines widest max] whose laid-out lines overrun, measured
   by `measure-for` (fn [style] -> measure)."
  [measure-for]
  (for [[w h] frames
        style styles
        lines captions
        :let [measure (measure-for style)
              {:keys [font-size-px max-line-px] laid :lines}
              (sut/layout {:width w :height h} style lines measure)
              widest (reduce max 0 (map #(measure font-size-px %) laid))]
        :when (> widest max-line-px)]
    [[w h] style lines widest max-line-px]))

(deftest every-line-fits-the-frame-by-the-burners-own-metrics
  (testing "java.awt metrics, the ones the Java2D burner draws with"
    (is (some? (font-metrics/awt-measure {})) "this JVM has fonts to measure")
    (is (empty? (worst-overflow font-metrics/measure))))
  (testing "the pure approximation"
    (is (empty? (worst-overflow sut/approximate-measure)))))

(deftest the-margin-is-inside-the-frame
  (doseq [[w _] frames]
    (is (< (sut/max-line-px w) w))
    (is (= (- w (* 2 (Math/round (* sut/margin-fraction w)))) (sut/max-line-px w)))))

(deftest the-card-caption-overflowed-portrait-before-and-fits-now
  (let [measure (font-metrics/measure {})]
    (doseq [[w h] [[1080 1920] [2160 3840]]]
      (let [size (captions/font-size-px h {})
            old  (reduce max (map #(measure size %) (overlay/wrap-line card-caption 42)))
            new  (sut/layout {:width w :height h} {} [card-caption] measure)]
        (is (> old w) (str w "x" h ": the character wrap alone ran off the frame"))
        (is (<= (:widest-px new) (:max-line-px new)))
        (is (= size (:font-size-px new))
            "a caption of ordinary words fits by breaking, not by shrinking")))))

(deftest landscape-defaults-keep-their-breaks-and-size
  (doseq [[w h] [[1280 720] [1920 1080] [3840 2160]]
          measure [(font-metrics/measure {}) (sut/approximate-measure {})]]
    (is (= {:font-size-px (captions/font-size-px h {})
            :lines (overlay/wrap-line card-caption 42)}
           (select-keys (sut/layout {:width w :height h} {} [card-caption] measure)
                        [:font-size-px :lines]))
        (str w "x" h ": the 42-character wrap still binds first"))))

(deftest a-word-wider-than-the-frame-shrinks-the-font
  (let [measure (sut/approximate-measure {})
        word    "Anticonstitucionalissimamente"
        {:keys [font-size-px widest-px max-line-px] :as out}
        (sut/layout {:width 1080 :height 1920} {:size-pct 12} [word] measure)]
    (is (< font-size-px (captions/font-size-px 1920 {:size-pct 12})))
    (is (<= widest-px max-line-px))
    (is (= [word] (:lines out)) "a word is never split")
    (testing "and the size is the largest that fits"
      (is (> (measure (inc font-size-px) word) max-line-px)))))

(deftest wrap-text-honours-both-bounds
  (let [width-of #(* 10 (count %))]
    (is (= ["one two" "three"] (sut/wrap-text "one two three" 8 1000 width-of)))
    (is (= ["one" "two" "three"] (sut/wrap-text "one two three" 100 60 width-of)))
    (is (= ["one two three"] (sut/wrap-text "one two three" nil 1000 width-of)))
    (is (= ["supercalifragilistic"] (sut/wrap-text "supercalifragilistic" 5 10 width-of)))))

(defspec layout-keeps-every-word-in-order 200
  (prop/for-all [words (gen/vector (gen/fmap #(apply str %)
                                             (gen/vector (gen/elements "abcdefghWM") 1 14))
                                   1 30)
                 w (gen/choose 120 3840)
                 h (gen/choose 120 3840)]
    (let [text (str/join " " words)
          {:keys [lines widest-px max-line-px font-size-px]}
          (sut/layout {:width w :height h} {} [text] (sut/approximate-measure {}))]
      (and (= words (mapcat #(str/split % #" ") lines))
           (or (<= widest-px max-line-px) (= 1 font-size-px))))))

;; --- the ASS script carries the layout --------------------------------------

(defn- dialogue-texts [doc]
  (->> (str/split-lines doc)
       (filter #(str/starts-with? % "Dialogue:"))
       (map #(second (re-find #"^Dialogue: (?:[^,]*,){9}(.*)$" %)))))

(deftest the-ass-script-draws-the-laid-out-lines
  (doseq [[w h] frames]
    (let [measure (font-metrics/measure {})
          cue     {:start-ms 0 :end-ms 1000 :lines [card-caption]}
          {:keys [lines]} (sut/layout {:width w :height h} {} [card-caption] measure)
          [text]  (dialogue-texts (ass/document {:width w :height h} {} [cue] measure))]
      (is (= (str/join "\\N" lines) text) (str w "x" h)))))

(deftest a-shrunk-cue-overrides-the-style-size
  (let [measure (sut/approximate-measure {})
        word    "Anticonstitucionalissimamente"
        style   {:size-pct 12}
        {:keys [font-size-px]} (sut/layout {:width 1080 :height 1920} style [word] measure)
        [text]  (dialogue-texts (ass/document {:width 1080 :height 1920} style
                                              [{:start-ms 0 :end-ms 1000 :lines [word]}]
                                              measure))]
    (is (= (str "{\\fs" font-size-px "\\bord" (ass/stroke-px font-size-px) "}" word) text))))
