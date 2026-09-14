(ns vtranslate.engine.collect.watermark
  "Draws the mark `calc.watermark` describes to a PNG on disk, for the burner
   to overlay. Java2D and ImageIO only: no native dependency, so this loads on
   every classpath the CLI burner does.

   Cached per size. A burn of eleven languages asks for the same 67px badge
   eleven times, and drawing it once is both faster and the only way every
   output carries a byte-identical mark."
  (:require [vtranslate.engine.calc.watermark :as calc])
  (:import [java.awt Color RenderingHints]
           [java.awt.geom Path2D$Double RoundRectangle2D$Double]
           [java.awt.image BufferedImage]
           [java.io File]
           [javax.imageio ImageIO]))

(defn- ->color
  ^Color [[r g b] opacity]
  (Color. (int r) (int g) (int b)
          (int (Math/round (* 255.0 (double opacity))))))

(defn- glyph-path
  ^Path2D$Double [size]
  (let [path   (Path2D$Double.)
        points (calc/glyph-polygon size)]
    (doseq [[i [x y]] (map-indexed vector points)]
      (if (zero? i)
        (.moveTo path (double x) (double y))
        (.lineTo path (double x) (double y))))
    (.closePath path)
    path))

(defn render
  "The mark as a `size`x`size` ARGB image: rounded mint tile, dark V.
   => BufferedImage"
  ^BufferedImage [size corner opacity]
  (let [size (int size)
        img  (BufferedImage. size size BufferedImage/TYPE_INT_ARGB)
        g2   (.createGraphics img)]
    (try
      (doto g2
        (.setRenderingHint RenderingHints/KEY_ANTIALIASING
                           RenderingHints/VALUE_ANTIALIAS_ON)
        (.setRenderingHint RenderingHints/KEY_STROKE_CONTROL
                           RenderingHints/VALUE_STROKE_PURE)
        (.setColor (->color (:tile calc/brand) opacity))
        (.fill (RoundRectangle2D$Double. 0.0 0.0 (double size) (double size)
                                         (double (* 2 corner)) (double (* 2 corner))))
        ;; The glyph is opaque against the tile rather than sharing its alpha:
        ;; two translucent layers over dark footage turn the V into a smudge.
        (.setColor (->color (:glyph calc/brand) 1.0))
        (.fill (glyph-path size)))
      img
      (finally (.dispose g2)))))

(defonce ^:private cache (atom {}))

(defn png-for
  "A PNG of the mark sized for a frame `height` pixels tall, written under
   `dir` and reused on every later call for the same height.

   The file is deliberately NOT deleted after a burn: it is a few hundred bytes
   in the worker's temp directory, and the next language of the same job wants
   the same one.
   => {:path :size :margin}"
  [dir height]
  (let [{:keys [size margin corner]} (calc/geometry height)
        key [(str dir) size corner]]
    {:path (or (get @cache key)
               (let [file (File. (str dir) (str "vtranslate-mark-" size ".png"))]
                 (ImageIO/write (render size corner (:opacity calc/defaults))
                                "png" file)
                 (let [path (.getPath file)]
                   (swap! cache assoc key path)
                   path)))
     :size size
     :margin margin}))
