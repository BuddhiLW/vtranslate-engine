(ns vtranslate.engine.collect.javacv-caption-fit-int-test
  "OPT-IN native suite (clojure -M:test:itest:ffmpeg): the Java2D burner's
   drawn caption stays inside the frame. Draws a laid-out caption onto a
   blank frame of each size in the matrix with collect.ffmpeg's own draw and
   reads back where the text pixels landed."
  (:require [clojure.test :refer [deftest is testing]]
            [vtranslate.engine.calc.caption-layout :as layout]
            [vtranslate.engine.calc.captions :as captions]
            [vtranslate.engine.calc.overlay :as overlay]
            [vtranslate.engine.collect.ffmpeg]
            [vtranslate.engine.collect.font-metrics :as font-metrics])
  (:import [java.awt.image BufferedImage]))

(def ^:private draw-lines! @#'vtranslate.engine.collect.ffmpeg/draw-lines!)

(def ^:private frames
  [[1080 1920] [2160 3840] [1280 720] [1920 1080] [3840 2160]])

(def ^:private caption
  "Ela ficou completamente chocada com aquela resposta inesperada do rapaz")

(def ^:private style
  "White text, black outline, no plate: every lit pixel is text."
  {:plate-opacity 0 :text-color "#ffffff" :outline-color "#000000"})

(defn- lit-columns
  "[min-x max-x] of the pixels brighter than mid-grey in `img`, or nil."
  [^BufferedImage img]
  (let [w (.getWidth img) h (.getHeight img)
        lit? (fn [x] (some #(< 128 (bit-and 0xff (.getRGB img (int x) (int %))))
                           (range 0 h 2)))
        xs (filter lit? (range w))]
    (when (seq xs) [(first xs) (last xs)])))

(deftest the-drawn-caption-lies-inside-the-margins
  (doseq [[w h] frames]
    (let [img     (BufferedImage. w h BufferedImage/TYPE_3BYTE_BGR)
          measure (font-metrics/measure style)
          laid    (layout/layout {:width w :height h} style [caption] measure)
          margin  (quot (- w (:max-line-px laid)) 2)
          stroke  (max 1 (quot (:font-size-px laid) 14))]
      (draw-lines! img laid style)
      (let [[x0 x1] (lit-columns img)]
        (testing (str w "x" h)
          (is (some? x0) "the caption was drawn")
          (is (>= x0 (- margin stroke)) "left edge inside the margin")
          (is (<= x1 (+ (- w margin) stroke)) "right edge inside the margin"))))))

(deftest portrait-overran-with-the-character-wrap-alone
  (doseq [[w h] [[1080 1920] [2160 3840]]]
    (let [img  (BufferedImage. w h BufferedImage/TYPE_3BYTE_BGR)
          size (captions/font-size-px h style)]
      (draw-lines! img {:lines (overlay/wrap-line caption 42) :font-size-px size} style)
      (let [[x0 x1] (lit-columns img)]
        (is (and (<= x0 1) (>= x1 (- w 2)))
            (str w "x" h ": the old breaks run off both edges"))))))
