(ns vtranslate.engine.calc.watermark-test
  "The VTranslate mark: its geometry, and the ffmpeg graph that composites it.

   Two of these assertions exist because the bug they describe was written and
   caught in a REPL against a real ffmpeg, not reasoned away:

   - `drawbox` and `drawtext` do not agree on what `w` means, which is why the
     mark is an overlaid image and not text at all;
   - a filtergraph label is CONSUMED by the filter that reads it, so the label
     the overlay reads cannot also be the label `-map` looks for."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [vtranslate.engine.calc.ffmpeg-args :as args]
            [vtranslate.engine.calc.watermark :as sut]))

(def ^:private plan
  {:width 1920 :height 1080 :rescaled? false
   :video-bitrate 2000000 :audio-bitrate 128000})

(deftest geometry-scales-with-the-frame
  (testing "the mark is a fraction of frame height, not a fixed pixel size"
    (let [big   (sut/geometry 1080)
          small (sut/geometry 360)]
      (is (> (:size big) (:size small)))
      (is (= (:size big) 67))
      (is (= (:margin big) 30))))

  (testing "a tiny output still gets a legible mark rather than a smudge"
    (let [tiny (sut/geometry 120)]
      (is (>= (:size tiny) (:min-size-px sut/defaults)))
      (is (>= (:margin tiny) (:min-margin-px sut/defaults)))))

  (testing "the corner radius is proportional, so the tile keeps its shape"
    (is (< 0 (:corner (sut/geometry 1080)) (:size (sut/geometry 1080))))))

(deftest the-glyph-is-a-polygon-not-a-font
  (testing "the V is described as points, so no font has to be installed"
    (is (= 7 (count sut/glyph-points)))
    (let [scaled (sut/glyph-polygon 64)]
      (is (= 7 (count scaled)))
      (is (every? (fn [[x y]] (and (<= 0 x 64) (<= 0 y 64))) scaled)
          "every point stays inside the tile it is drawn on")))

  (testing "the glyph scales with the tile"
    (is (= (mapv (fn [[x y]] [(* 2 x) (* 2 y)]) (sut/glyph-polygon 32))
           (sut/glyph-polygon 64)))))

(deftest the-mark-sits-in-the-top-right
  (let [[x y] (sut/overlay-position (sut/geometry 1080))]
    (testing "x is measured from the right edge, in the overlay filter's own
              vocabulary (W is the frame, w the overlay)"
      (is (= "W-w-30" x)))
    (testing "y is measured from the top, away from the captions at 0.94"
      (is (= "30" y)))))

(deftest burn-args-without-a-mark-is-unchanged
  (let [argv (args/burn-args {:source "in.mp4" :out "out" :ass-path "s.ass"
                              :plan plan :audio? true})]
    (is (= 1 (count (filter #{"-i"} argv))) "one input")
    (is (some #{"-vf"} argv) "the plain single-input filter chain")
    (is (not-any? #{"-filter_complex" "-map"} argv))))

(deftest burn-args-with-a-mark-maps-both-streams
  (let [argv  (args/burn-args {:source "in.mp4" :out "out" :ass-path "s.ass"
                               :plan plan :audio? true
                               :watermark {:png "/tmp/mark.png"
                                           :position ["W-w-30" "30"]}})
        graph (second (drop-while (complement #{"-filter_complex"}) argv))
        maps  (->> argv (partition 2 1) (keep (fn [[a b]] (when (= "-map" a) b))))]
    (testing "the mark is a second input"
      (is (= 2 (count (filter #{"-i"} argv))))
      (is (some #{"/tmp/mark.png"} argv)))

    (testing "the graph ends on the label -map asks for"
      (is (str/ends-with? graph (str "[" args/watermark-out-label "]")))
      (is (some #{(str "[" args/watermark-out-label "]")} maps)
          "mapping a label the graph does not leave behind is the
           'Output with label vid does not exist' failure"))

    (testing "the intermediate label is not the mapped one"
      (is (str/includes? graph "[subbed];")
          "the overlay consumes what it reads, so the subtitled stage needs
           its own name")
      (is (not (str/includes? graph "[vid];"))))

    (testing "audio is mapped explicitly, because a second input makes
              ffmpeg's default selection ambiguous and the PNG has none"
      (is (some #{"0:a"} maps)))

    (testing "-vf is not also passed"
      (is (not-any? #{"-vf"} argv)))))

(deftest a-silent-source-with-a-mark-still-drops-audio
  (let [argv (args/burn-args {:source "in.mp4" :out "out" :ass-path "s.ass"
                              :plan plan :audio? false
                              :watermark {:png "/tmp/mark.png"
                                          :position ["W-w-30" "30"]}})
        maps (->> argv (partition 2 1) (keep (fn [[a b]] (when (= "-map" a) b))))]
    (is (some #{"-an"} argv))
    (is (not-any? #{"0:a"} maps)
        "mapping an audio stream that is not there fails the run")))
