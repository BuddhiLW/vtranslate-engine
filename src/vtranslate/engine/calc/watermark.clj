(ns vtranslate.engine.calc.watermark
  "The VTranslate mark, as geometry. Pure: numbers in, numbers out, nothing
   drawn and nothing written.

   The mark is the logo: a mint tile with the corners rounded, carrying a dark
   V. It is described here as a polygon rather than as TEXT because a glyph
   needs a font, and a font is a thing a container can be missing. A burn that
   fails because the image has no DejaVu is a cosmetic feature taking the whole
   job down with it.

   Sizes are FRACTIONS of frame height, never pixels, for the same reason the
   caption style is: one mark has to look the same at 360p and at 4K.")

(def brand
  "The two colours of the mark, as the logo defines them (resources/public/
   img/mark.svg). `tile` is the mint field, `glyph` the near-black V."
  {:tile  [0xb8 0xf3 0x4a]
   :glyph [0x17 0x20 0x04]})

(def defaults
  "How big the mark is and where it sits.

   TOP-right, not bottom-right: captions sit at 0.94 of frame height, and a
   badge in that corner would sooner or later land on a line of dialogue.

   `opacity` is short of 1 so the mark reads as an overlay rather than as part
   of the picture, and `min-size-px` keeps it legible on a small output where
   the fraction alone would round it to a smudge."
  {:size-pct    0.062
   :margin-pct  0.028
   :min-size-px 22
   :min-margin-px 12
   :corner-pct  0.28
   :opacity     0.92})

(defn- scaled
  "A fraction of `height` in whole pixels, never below `floor`."
  ^long [height fraction floor]
  (max (long floor)
       (long (Math/round (* (double height) (double fraction))))))

(defn geometry
  "Where and how big the mark is on a frame `height` pixels tall.
   => {:size :margin :corner} in whole pixels."
  ([height] (geometry height defaults))
  ([height opts]
   (let [{:keys [size-pct margin-pct min-size-px min-margin-px corner-pct]}
         (merge defaults opts)
         size (scaled height size-pct min-size-px)]
     {:size   size
      :margin (scaled height margin-pct min-margin-px)
      :corner (max 2 (long (Math/round (* size (double corner-pct)))))})))

(def glyph-viewbox
  "The square the V below is described in, matching the logo's own viewBox so
   the proportions are the logo's and not a redrawing of it."
  32.0)

(def glyph-points
  "The V as one closed polygon in `glyph-viewbox` coordinates, wound from the
   top-left outer corner: down the left stroke to the inner vertex, up to the
   right shoulder, out to the right outer corner, then down to the flat foot
   and back.

   A polygon and not two strokes, because a stroked path needs a join rule to
   decide what the vertex looks like and a filled outline does not."
  [[7.0 8.0] [12.0 8.0] [16.0 19.0] [20.0 8.0] [25.0 8.0] [18.0 24.0] [14.0 24.0]])

(defn glyph-polygon
  "`glyph-points` scaled to a tile `size` pixels on a side.
   => [[x y] ...] in pixels, origin at the tile's top-left."
  [size]
  (let [k (/ (double size) glyph-viewbox)]
    (mapv (fn [[x y]] [(* k x) (* k y)]) glyph-points)))

(defn overlay-position
  "Where the mark's top-left corner goes, as the ffmpeg `overlay` filter wants
   it: expressions over the main frame's W and the overlay's own w.
   => [x-expr y-expr]"
  [{:keys [margin]}]
  [(str "W-w-" margin) (str margin)])
