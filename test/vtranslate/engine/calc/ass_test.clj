(ns vtranslate.engine.calc.ass-test
  "Golden + property for the pure ASS script calc: clock format, colour
   packing, text escaping, one Dialogue per showable cue, and a style row
   that follows calc.captions. Strings only, no IO."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [hive-test.golden :refer [deftest-golden]]
            [vtranslate.engine.calc.caption-layout :as layout]
            [vtranslate.engine.calc.captions :as captions]
            [vtranslate.engine.calc.ass :as sut]))

(deftest timestamp-is-hours-minutes-seconds-centiseconds
  (is (= "0:00:00.00" (sut/timestamp 0)))
  (is (= "0:00:04.00" (sut/timestamp 4000)))
  (is (= "0:01:05.99" (sut/timestamp 65999)) "centiseconds truncate, never round up")
  (is (= "1:02:03.45" (sut/timestamp (+ 3600000 120000 3000 456))))
  (is (= "0:00:00.00" (sut/timestamp -5)) "a negative time reads as zero"))

(deftest colour-packs-abgr-with-inverted-alpha
  (is (= "&H00FFFFFF" (sut/colour [255 255 255] 0)) "opaque white")
  (is (= "&H73000000" (sut/colour [0 0 0] 115)) "plate: black at the given transparency")
  (is (= "&H00C0FF10" (sut/colour [16 255 192] 0)) "order is blue, green, red")
  (is (= "&HFF0000FF" (sut/colour [999 -1 0] 300)) "components clamp to a byte"))

(deftest escape-neutralises-override-braces-and-newlines
  (is (= "a｛b｝c" (sut/escape-text "a{b}c")))
  (is (= "one\\Ntwo" (sut/escape-text "one\ntwo")))
  (is (= "one\\Ntwo" (sut/escape-text "one\r\ntwo")))
  (is (= "back\\\\slash" (sut/escape-text "back\\slash")))
  (is (= "" (sut/escape-text nil))))

(deftest font-name-maps-java-logical-fonts-to-fontconfig-generics
  (is (= "sans-serif" (sut/font-name "SansSerif")))
  (is (= "sans-serif" (sut/font-name nil)) "the captions default is a logical sans")
  (is (= "serif" (sut/font-name "Serif")))
  (is (= "monospace" (sut/font-name "Monospaced")))
  (is (= "Noto Sans" (sut/font-name "Noto Sans")) "a real family passes through"))

(deftest dialogue-line-skips-what-cannot-show
  (is (nil? (sut/dialogue-line {:start-ms 1000 :end-ms 1000 :lines ["x"]} 45 nil))
      "zero-length cue")
  (is (nil? (sut/dialogue-line {:start-ms 2000 :end-ms 1000 :lines ["x"]} 45 nil))
      "cue ending before it starts")
  (is (nil? (sut/dialogue-line {:start-ms 0 :end-ms 1000 :lines ["" "  "]} 45 nil))
      "only blank lines")
  (is (= "Dialogue: 0,0:00:01.00,0:00:02.50,Default,,0,0,0,,hello\\Nworld"
         (sut/dialogue-line {:start-ms 1000 :end-ms 2500 :lines ["hello" "world"]} 45 nil))))

(deftest dialogue-line-draws-the-layout-it-is-given
  (let [lay-out (fn [lines]
                  (layout/layout {:width 1920 :height 1080} {:wrap 8} lines
                                 (layout/approximate-measure {})))]
    (is (= "Dialogue: 0,0:00:00.00,0:00:01.00,Default,,0,0,0,,one two\\Nthree"
           (sut/dialogue-line {:start-ms 0 :end-ms 1000 :lines ["one two three"]} 45 lay-out))
        "the breaks are the layout's; the size matches the style row, so no override")
    (is (= "Dialogue: 0,0:00:00.00,0:00:01.00,Default,,0,0,0,,{\\fs30\\bord2}big"
           (sut/dialogue-line {:start-ms 0 :end-ms 1000 :lines ["big"]} 45
                              (constantly {:lines ["big"] :font-size-px 30})))
        "a size the layout shrank rides as an override tag")))

(deftest style-row-follows-calc-captions
  (let [row (sut/style-row 1920 1080 {})
        [_ font size primary _secondary outline back bold & more] (str/split row #",")]
    (testing "defaults on a 1080p frame"
      (is (= "sans-serif" font))
      (is (= (str (captions/font-size-px 1080 {})) size) "4.2% of 1080 = 45px")
      (is (= "&H00FFFFFF" primary))
      (is (= "&H00000000" outline))
      (is (= (sut/colour [0 0 0] (- 255 (captions/plate-alpha {}))) back)
          "plate colour carries the plate opacity")
      (is (= "-1" bold))
      (let [[_i _u _s _sx _sy _sp _a border stroke _sh align ml mr margin-v enc] more]
        (is (= "4" border) "a visible plate is a per-line box behind outlined text")
        (is (= (str (max 1 (quot (captions/font-size-px 1080 {}) 14))) stroke))
        (is (= "2" align) "bottom centre")
        (is (= ["77" "77"] [ml mr]) "4% of the width on each side")
        (is (= (str (- 1080 (captions/block-bottom-px 1080 {}))) margin-v)
            "MarginV is the gap below the block")
        (is (= "1" enc)))))
  (testing "no plate means outline only"
    (let [fields (str/split (sut/style-row 1280 720 {:plate-opacity 0}) #",")]
      (is (= "1" (nth fields 15)))))
  (testing "portrait frames get a margin from their own width"
    (let [fields (str/split (sut/style-row 1080 1920 {}) #",")]
      (is (= ["43" "43"] (subvec fields 19 21)))))
  (testing "explicit pixel size wins"
    (is (= "24" (nth (str/split (sut/style-row 320 240 {:size-px 24}) #",") 2)))))

(deftest-golden ass-document-golden
  "test/golden/ass-document.edn"
  (sut/document {:width 1280 :height 720}
                {:wrap 12 :plate-opacity 0.5 :text-color "#ffff00"}
                [{:start-ms 4000 :end-ms 8000 :lines ["Sample subtitle line" "two"]}
                 {:start-ms 8000 :end-ms 8000 :lines ["skipped"]}
                 {:start-ms 16000 :end-ms 20500 :lines ["braces {here}"]}]))

;; --- property ---------------------------------------------------------------

(def ^:private gen-line
  (gen/fmap #(apply str %) (gen/vector (gen/elements "abc de{}\\ ") 0 30)))

(def ^:private gen-cue
  (gen/let [start (gen/choose 0 3600000)
            len   (gen/choose 0 10000)
            lines (gen/vector gen-line 0 3)]
    {:start-ms start :end-ms (+ start len) :lines lines}))

(defn- showable? [{:keys [start-ms end-ms lines]}]
  (and (< start-ms end-ms) (some (complement str/blank?) lines)))

(defspec one-dialogue-per-showable-cue-and-no-raw-braces 100
  (prop/for-all [cues (gen/vector gen-cue 0 12)
                 wrap (gen/one-of [(gen/return nil) (gen/choose 1 20)])]
    (let [doc      (sut/document {:width 640 :height 360} {:wrap wrap} cues)
          dialogue (filter #(str/starts-with? % "Dialogue:") (str/split-lines doc))]
      (and (= (count (filter showable? cues)) (count dialogue))
           (not-any? #(re-find #"[{}]" %) dialogue)
           (every? #(re-find #"^Dialogue: 0,\d+:\d\d:\d\d\.\d\d,\d+:\d\d:\d\d\.\d\d,Default,,0,0,0,," %)
                   dialogue)))))
