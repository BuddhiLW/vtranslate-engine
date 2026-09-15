(ns vtranslate.engine.calc.caption-layout
  "Pure line breaking and font fitting for burned captions, by MEASURED width.

   A caption is laid out against a frame: its font size starts at
   calc.captions/font-size-px, its words are broken greedily so every line is
   at most `:wrap` characters AND at most `max-line-px` wide, and when a single
   word is still wider than that the font size shrinks until the widest line
   fits. Both burners (the ASS script for libass and the Java2D draw) draw the
   result, so the two break text identically.

   Width is measured through a `measure` function (fn [font-size-px text] ->
   px), supplied by the caller: the Collect boundary passes real font metrics,
   and `approximate-measure` is the pure stand-in for callers without one.
   No AWT, no IO."
  (:require [clojure.string :as str]
            [vtranslate.engine.calc.captions :as captions]))

(def margin-fraction
  "Space kept free on EACH side of the frame, as a fraction of its width.
   Wider than calc.ass/side-margin-fraction, the box libass wraps inside."
  0.06)

(defn max-line-px
  "Widest a caption line may be on a frame `width` px wide: the width minus
   `margin-fraction` of it on each side. At least 1."
  ^long [width]
  (max 1 (- (long width)
            (* 2 (long (Math/round (* margin-fraction (double width))))))))

;; ---------------------------------------------------------------------------
;; The pure measure
;; ---------------------------------------------------------------------------

(def ^:private ascii-advance-per-mille
  "DejaVu Sans Bold advance widths for code points 32..126, in thousandths of
   an em."
  [348 456 521 838 696 1002 872 306 457 457 523 838 380 415 380 365 696 696
   696 696 696 696 696 696 696 696 400 400 838 838 838 580 1000 774 762 734
   830 683 683 821 837 372 372 775 637 995 837 850 733 850 770 720 682 812 774
   1103 771 724 725 457 365 457 838 500 500 675 716 593 716 678 435 716 712 343
   343 665 343 1042 712 687 716 716 493 595 478 712 652 924 645 652 582 712 365
   712 838])

(def ^:private other-advance-per-mille
  "Advance assumed for a code point outside ASCII and below the CJK blocks
   (accented Latin, Cyrillic, Greek, Arabic, Hebrew): a wide lowercase letter."
  720)

(def ^:private wide-advance-per-mille
  "Advance for CJK, kana, hangul and fullwidth forms: a full em."
  1000)

(defn- advance-per-mille ^long [^long cp]
  (cond
    (<= 32 cp 126) (long (nth ascii-advance-per-mille (- cp 32)))
    (< cp 32)      0
    (>= cp 0x2E80) wide-advance-per-mille
    :else          other-advance-per-mille))

(defn approximate-measure
  "A pure `measure` for `requested` style: the width `text` would have at
   `font-size-px`, summed from DejaVu Sans Bold advances (the face the worker
   image burns with). A regular weight is taken as 90% of bold; a monospaced
   family as 0.602 em per character."
  [requested]
  (let [{:keys [bold? font-family]} (captions/style requested)
        mono?  (contains? #{"Monospaced" "monospace" "Mono"} (str font-family))
        weight (if bold? 1.0 0.9)]
    (fn [font-size-px text]
      (let [s   (str text)
            ems (if mono?
                  (* 602.0 (.codePointCount s 0 (count s)))
                  (reduce + 0.0 (map #(advance-per-mille %)
                                     (iterator-seq (.iterator (.codePoints s))))))]
        (* ems weight (/ (double font-size-px) 1000.0))))))

;; ---------------------------------------------------------------------------
;; Breaking and fitting
;; ---------------------------------------------------------------------------

(defn wrap-text
  "Greedy word wrap of `text` => vector of lines, each at most `max-chars`
   characters (ignored when nil or not positive) and at most `max-px` wide by
   `width-of` (fn [text] -> px). A single word longer than either stays whole
   on its own line. Text that already fits is returned as it is."
  [text max-chars max-px width-of]
  (let [text     (str text)
        chars-ok (fn [s] (or (nil? max-chars) (not (pos? max-chars))
                             (<= (count s) max-chars)))
        fits?    (fn [s] (and (chars-ok s) (<= (double (width-of s)) (double max-px))))]
    (if (fits? text)
      [text]
      (reduce (fn [lines word]
                (let [cur (peek lines)
                      joined (when cur (str cur " " word))]
                  (if (and joined (fits? joined))
                    (conj (pop lines) joined)
                    (conj lines word))))
              []
              (remove empty? (str/split (str/trim text) #"\s+"))))))

(defn- wrap-all [lines wrap max-px width-of]
  (vec (mapcat #(wrap-text % wrap max-px width-of) lines)))

(defn- widest [lines width-of]
  (reduce max 0.0 (map #(double (width-of %)) lines)))

(defn layout
  "The caption `lines` laid out on a `width` x `height` frame in `requested`
   style, measured by `measure` (fn [font-size-px text] -> px).

   => {:font-size-px n :lines [line ...] :widest-px w :max-line-px m}

   `:lines` are the display lines after the wrap. `:font-size-px` is
   calc.captions/font-size-px unless a line was still wider than `:max-line-px`
   at that size, in which case it is the largest size (down to 1px) at which
   every line, broken again at that size, fits. Fitting wins over the
   `:min-size-px` floor."
  [{:keys [width height]} requested lines measure]
  (let [wrap    (:wrap (captions/style requested))
        max-px  (max-line-px width)
        lines   (vec (remove nil? lines))]
    (loop [size (captions/font-size-px height requested)]
      (let [width-of #(measure size %)
            wrapped  (wrap-all lines wrap max-px width-of)
            w        (widest wrapped width-of)]
        (if (or (<= w max-px) (<= size 1))
          {:font-size-px size
           :lines wrapped
           :widest-px (long (Math/ceil w))
           :max-line-px max-px}
          (recur (max 1 (min (dec size)
                             (long (Math/floor (* size (/ (double max-px) w))))))))))))
