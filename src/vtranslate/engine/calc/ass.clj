(ns vtranslate.engine.calc.ass
  "Pure ASS (SSA v4.00+) script generation for a burn rendered by libass: the
   calc.captions style the Java2D path draws, expressed as one [V4+ Styles] row,
   and one Dialogue line per overlay cue. Strings in, one string out. No IO."
  (:require [clojure.string :as str]
            [vtranslate.engine.calc.captions :as captions]
            [vtranslate.engine.calc.overlay :as overlay]))

(defn timestamp
  "ASS clock for `ms`: H:MM:SS.cc, centiseconds truncated. A negative time
   reads as zero; the format has no sign."
  ^String [ms]
  (let [ms (max 0 (long ms))
        cs (quot (rem ms 1000) 10)
        s  (rem (quot ms 1000) 60)
        m  (rem (quot ms 60000) 60)
        h  (quot ms 3600000)]
    (format "%d:%02d:%02d.%02d" h m s cs)))

(defn colour
  "ASS colour &HAABBGGRR for `[r g b]` and an `alpha` where 0 is opaque and
   255 fully transparent (the reverse of AWT). Components are clamped."
  ^String [[r g b] alpha]
  (let [clamp (fn [n] (max 0 (min 255 (long n))))]
    (format "&H%02X%02X%02X%02X" (clamp alpha) (clamp b) (clamp g) (clamp r))))

(defn font-name
  "libass resolves families through fontconfig, which knows the generic
   aliases but not Java's logical font names. The three logical names map to
   their generic; anything else is passed through as written."
  ^String [family]
  (case (some-> family str)
    ("SansSerif" "sans-serif" "Sans" nil "") "sans-serif"
    ("Serif" "serif")                        "serif"
    ("Monospaced" "monospace" "Mono")        "monospace"
    ("Dialog" "DialogInput")                 "sans-serif"
    (str family)))

(defn escape-text
  "Cue text safe inside a Dialogue line. Braces open override blocks in ASS,
   so they are swapped for their fullwidth forms rather than dropped; hard
   newlines become \\N; a bare backslash is kept literal."
  ^String [s]
  (-> (str s)
      (str/replace "\\" "\\\\")
      (str/replace "{" "｛")
      (str/replace "}" "｝")
      (str/replace #"\r?\n" "\\\\N")))

(defn wrapped-lines
  "The display lines of a cue after the style's `:wrap`, the same greedy wrap
   the Java2D path applies, so both backends break text identically."
  [lines wrap]
  (if wrap
    (vec (mapcat #(overlay/wrap-line % wrap) lines))
    (vec lines)))

(def side-margin-fraction
  "Horizontal margin on each side as a fraction of the frame width. Inside
   it libass re-breaks a line the character wrap left too wide for the
   frame, which is what happens to a 42-character line on a 9:16 video."
  0.04)

(defn side-margin-px
  "MarginL and MarginR for a frame `width` px wide."
  ^long [width]
  (long (Math/round (* side-margin-fraction (double width)))))

(defn style-row
  "The single [V4+ Styles] row for a `width` x `height` frame and `requested`
   caption style. Alignment 2 is bottom centre; MarginV is the distance from
   the bottom edge up to the block's bottom, which is how calc.captions places
   the block. BorderStyle 4 draws a box behind each line AND keeps the text
   outline (a libass extension the Ubuntu build ships); with a zero plate
   opacity it falls back to 1, outline only."
  ^String [width height requested]
  (let [resolved  (captions/style requested)
        font-size (captions/font-size-px height requested)
        alpha     (captions/plate-alpha requested)
        {:keys [text outline plate]} (captions/colors requested)
        margin-v  (- (long height) (captions/block-bottom-px height requested))
        margin-lr (side-margin-px width)
        stroke    (max 1 (quot font-size 14))
        border    (if (pos? alpha) 4 1)]
    (str/join "," ["Style: Default"
                   (font-name (:font-family resolved))
                   font-size
                   (colour text 0)
                   (colour text 0)
                   (colour outline 0)
                   (colour plate (- 255 alpha))
                   (if (:bold? resolved) -1 0)
                   0 0 0
                   100 100 0 0
                   border stroke 0
                   2
                   margin-lr margin-lr margin-v
                   1])))

(defn dialogue-line
  "One Events row for a plain overlay cue {:start-ms :end-ms :lines}, or nil
   for a cue that ends before it starts or has nothing to show."
  [{:keys [start-ms end-ms lines]} wrap]
  (let [shown (remove str/blank? (wrapped-lines lines wrap))]
    (when (and (seq shown) (< (long start-ms) (long end-ms)))
      (str "Dialogue: 0," (timestamp start-ms) "," (timestamp end-ms)
           ",Default,,0,0,0,,"
           (str/join "\\N" (map escape-text shown))))))

(def ^:private styles-format
  "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding")

(def ^:private events-format
  "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text")

(defn document
  "The whole script for `cues` (plain overlay cues, as calc.overlay/timeline
   yields) on a `width` x `height` frame in `requested` style. PlayRes pins
   the script's coordinate space to the frame, so a font size in pixels here
   is the same size the Java2D path draws. The lines arrive already broken
   by `:wrap`, a character count; WrapStyle 0 lets libass break again, evenly,
   any line that is still wider than the frame minus the side margins, so a
   portrait video does not get a caption running off both edges."
  ^String [{:keys [width height]} requested cues]
  (let [wrap (:wrap (captions/style requested))]
    (str/join "\n"
              (concat ["[Script Info]"
                       "ScriptType: v4.00+"
                       (str "PlayResX: " (long width))
                       (str "PlayResY: " (long height))
                       "WrapStyle: 0"
                       "ScaledBorderAndShadow: yes"
                       ""
                       "[V4+ Styles]"
                       styles-format
                       (style-row width height requested)
                       ""
                       "[Events]"
                       events-format]
                      (keep #(dialogue-line % wrap) cues)
                      [""]))))
