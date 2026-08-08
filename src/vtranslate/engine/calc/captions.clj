(ns vtranslate.engine.calc.captions
  "Pure typography and placement for burned-in captions. No AWT, no IO — the
   Collect boundary owns the pixels."
  (:require [clojure.string :as str]))

(def defaults
  "Caption style when the caller asks for nothing.

   `:size-pct` and `:y-position` are FRACTIONS of frame height, never pixels: a
   size that reads well on 1080p is unreadable on 360p and enormous on 4K. The
   same fractions drive the browser's preview, so what is previewed is what is
   burned."
  {:font-family "SansSerif"
   :bold? true
   :size-pct 4.2
   :min-size-px 18
   ;; Where the bottom of the text block sits, measured from the top. 0.94
   ;; leaves the usual safe margin; lower it to clear a burned-in logo.
   :y-position 0.94
   :wrap 42
   :text-color "#ffffff"
   :outline-color "#000000"
   :plate-color "#000000"
   ;; 0 hides the plate entirely, leaving outlined text over the picture.
   :plate-opacity 0.55})

(defn style
  "`requested` merged over the defaults, with nils treated as absent so a
   partially-filled form does not blank the rest."
  [requested]
  (merge defaults (into {} (remove (comp nil? val)) (or requested {}))))

(defn font-size-px
  "Pixel font size for a frame `height` px tall.

   An explicit `:size-px` wins; otherwise the size is a percentage of the frame
   so it reads the same at every resolution."
  ^long [height requested]
  (let [{:keys [size-px size-pct min-size-px]} (style requested)]
    (if (and size-px (pos? (long size-px)))
      (long size-px)
      (max (long min-size-px)
           (long (* (/ (double size-pct) 100.0) (long height)))))))

(defn block-bottom-px
  "Y pixel the bottom of the caption block sits on, clamped inside the frame."
  ^long [height requested]
  (let [{:keys [y-position]} (style requested)
        y (long (* (double y-position) (long height)))]
    (max 0 (min (long height) y))))

(defn baseline-px
  "Baseline Y for line `index` of a `line-count`-line block.

   Lines stack upward from the block's bottom, so adding a second line pushes
   the first one up rather than pushing the block off the frame."
  [height requested line-count line-height index]
  (- (block-bottom-px height requested)
     (* (long line-height) (- (long line-count) 1 (long index)))))

(defn plate-alpha
  "Opacity of the plate behind the text, as an AWT alpha 0-255."
  ^long [requested]
  (let [opacity (double (or (:plate-opacity (style requested)) 0.55))]
    (max 0 (min 255 (long (Math/round (* 255.0 opacity)))))))

(def ^:private default-rgb [255 255 255])

(defn rgb
  "`#rrggbb` (or `#rgb`) as [r g b]. An unreadable colour falls back to white
   rather than throwing: a malformed hex must not fail a job that has already
   paid for its ASR."
  [hex]
  (let [s (some-> hex str (str/replace "#" "") str/trim)]
    (cond
      (and s (= 6 (count s)))
      (try (mapv #(Integer/parseInt (apply str %) 16) (partition 2 s))
           (catch Exception _ default-rgb))

      (and s (= 3 (count s)))
      (try (mapv #(Integer/parseInt (str % %) 16) s)
           (catch Exception _ default-rgb))

      :else default-rgb)))

(defn colors
  "Resolved [text outline plate] colours, each as [r g b]."
  [requested]
  (let [{:keys [text-color outline-color plate-color]} (style requested)]
    {:text (rgb text-color)
     :outline (rgb outline-color)
     :plate (rgb plate-color)}))
