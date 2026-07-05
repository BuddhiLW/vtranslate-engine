(ns vtranslate.engine.calc.reflow
  "Promote (CPPB) — pure: cutting phase B. Shape parsed subtitle cue-maps by a
   data-driven rule table BEFORE they are promoted into a SubtitleTrack. Every
   rule is a pure [cue-map] -> [cue-map] transform, selected by a config key, so
   the pipeline is open for extension (add a rule) and closed for modification
   (OCP). Operates on the flat parser cue-map {:index :start-ms :end-ms :lines}
   (integer ms); overlap-merge and 1-based re-indexing always run, the rest are
   opt-in. Total + IO-free — no Result, mirrors the pure grid-spans tiler.

   Reading order of the pipeline: drop non-speech -> merge overlaps -> cap long
   -> extend short -> enforce gap -> wrap lines -> split high-CPS -> snap grid ->
   re-index."
  (:require [clojure.string :as str]))

;; --- cue-map accessors (pure) ----------------------------------------------

(defn- cue-dur [{:keys [start-ms end-ms]}]
  (- end-ms start-ms))

(defn- text-len
  "Total character count across a cue's lines (the CPS numerator)."
  [{:keys [lines]}]
  (reduce + 0 (map count lines)))

(defn- words
  "Whitespace-tokenize a cue's lines into a flat vector of non-blank words."
  [lines]
  (->> lines
       (mapcat #(str/split (str/trim %) #"\s+"))
       (remove str/blank?)
       vec))

;; --- line wrapping ---------------------------------------------------------

(defn- wrap-lines
  "Greedy word-wrap `lines` so each result line is <= `max-chars`. Western text
   wraps at spaces; a single token longer than a line (e.g. spaceless CJK) is
   hard-split by character. Never returns an empty vector for non-blank input."
  [max-chars lines]
  (loop [ws (words lines), cur "", out []]
    (if (empty? ws)
      (if (str/blank? cur) out (conj out cur))
      (let [w (first ws)]
        (cond
          (<= (+ (count cur) (if (str/blank? cur) 0 1) (count w)) max-chars)
          (recur (rest ws) (if (str/blank? cur) w (str cur " " w)) out)

          (not (str/blank? cur))
          (recur ws "" (conj out cur))

          (> (count w) max-chars)
          (recur (cons (subs w max-chars) (rest ws)) "" (conj out (subs w 0 max-chars)))

          :else
          (recur (rest ws) w out))))))

;; --- rule: drop non-speech (music / sound markers) -------------------------

(defn- marker-line?
  "A non-speech line: an italic/parenthetical/bracketed sound cue, or a music
   line — wrapped in or made entirely of ♪ ♫ note glyphs — tolerating a leading
   speaker dash and simple inline tags."
  [line]
  (let [t     (-> line
                  (str/replace #"</?[ibu]>" "")
                  str/trim
                  (as-> s (if (str/starts-with? s "- ") (subs s 2) s))
                  str/trim)
        notes #{\♪ \♫ \〽}]
    (or (str/blank? t)
        (and (str/starts-with? t "(") (str/ends-with? t ")"))
        (and (str/starts-with? t "[") (str/ends-with? t "]"))
        (and (contains? notes (first t)) (contains? notes (last t)))
        (every? (fn [ch] (or (Character/isWhitespace ^char ch)
                             (contains? notes ch)))
                t))))

(defn- music-cue? [{:keys [lines]}]
  (and (seq lines) (every? marker-line? lines)))

(defn- drop-music [cue-maps]
  (vec (remove music-cue? cue-maps)))

;; --- rule: merge overlapping cues (structural normalization) ---------------

(defn- merge-overlaps
  "Sort by time and fold truly-overlapping cues (next start < current end) into
   one — union time span, concatenated lines. Touching cues (end == next start)
   are left intact."
  [cue-maps]
  (->> cue-maps
       (sort-by (juxt :start-ms :end-ms))
       (reduce (fn [acc c]
                 (let [prev (peek acc)]
                   (if (and prev (< (:start-ms c) (:end-ms prev)))
                     (conj (pop acc)
                           (-> prev
                               (assoc :end-ms (max (:end-ms prev) (:end-ms c)))
                               (update :lines into (:lines c))))
                     (conj acc c))))
               [])))

;; --- rule: duration bounds -------------------------------------------------

(defn- cap-duration
  "Truncate any cue longer than `max-dur` to exactly `max-dur`."
  [max-dur cue-maps]
  (mapv (fn [c]
          (if (> (cue-dur c) max-dur)
            (assoc c :end-ms (+ (:start-ms c) max-dur))
            c))
        cue-maps))

(defn- extend-duration
  "Extend any cue shorter than `min-dur` up to `min-dur`, never past the next
   cue's start (minus `min-gap`, if set). Starts are never moved."
  [min-dur min-gap cue-maps]
  (let [gap (or min-gap 0)]
    (vec (map-indexed
          (fn [i c]
            (let [nxt     (get cue-maps (inc i))
                  ceiling (if nxt (- (:start-ms nxt) gap) Long/MAX_VALUE)
                  want    (+ (:start-ms c) min-dur)
                  new-end (min (max (:end-ms c) want) (max (:start-ms c) ceiling))]
              (assoc c :end-ms new-end)))
          cue-maps))))

(defn- enforce-gap
  "Guarantee at least `min-gap` ms before the next cue by pulling the earlier
   cue's end back (clamped to its own start). Starts are never moved."
  [min-gap cue-maps]
  (vec (map-indexed
        (fn [i c]
          (let [nxt (get cue-maps (inc i))]
            (if nxt
              (let [max-end (- (:start-ms nxt) min-gap)]
                (if (< max-end (:end-ms c))
                  (assoc c :end-ms (max (:start-ms c) max-end))
                  c))
              c)))
        cue-maps)))

;; --- rule: line wrap -------------------------------------------------------

(defn- wrap-cues [max-chars cue-maps]
  (mapv (fn [c]
          (let [wrapped (wrap-lines max-chars (:lines c))]
            (assoc c :lines (if (seq wrapped) wrapped (:lines c)))))
        cue-maps))

;; --- rule: split high-CPS cues over their time span ------------------------

(defn- balanced-chunks
  "Split `ws` (a word vector) into at most `n` groups, balancing total char
   length. No empty groups; may return fewer than `n` groups."
  [n ws]
  (let [total (reduce + 0 (map count ws))]
    (if (or (<= n 1) (< (count ws) 2) (zero? total))
      [ws]
      (let [target (/ total n)]
        (loop [ws ws, acc-len 0, cur [], groups []]
          (if (empty? ws)
            (if (seq cur) (conj groups cur) groups)
            (let [w    (first ws)
                  cur' (conj cur w)
                  len' (+ acc-len (count w))]
              (if (and (>= len' target) (< (inc (count groups)) n))
                (recur (rest ws) 0 [] (conj groups cur'))
                (recur (rest ws) len' cur' groups)))))))))

(defn- piece-lines [max-chars group]
  (if max-chars
    (wrap-lines max-chars group)
    [(str/join " " group)]))

(defn- split-cue
  "Split one cue whose reading speed exceeds `max-cps` into sequential sub-cues,
   distributing time proportional to each chunk's char length so every piece
   lands at/under the target (when duration allows) and within `max-lines` *
   `max-chars`. Zero/negative-duration or textless cues are left untouched."
  [max-cps max-chars max-lines c]
  (let [dur   (cue-dur c)
        chars (text-len c)]
    (if (or (<= dur 0) (<= chars 0))
      [c]
      (let [cps      (/ (* chars 1000.0) dur)
            need-cps (long (Math/ceil (/ cps (double max-cps))))
            need-len (if (and max-chars max-lines)
                       (long (Math/ceil (/ chars (double (* max-chars max-lines)))))
                       1)
            n        (min (max 1 need-cps need-len) dur)]
        (if (<= n 1)
          [c]
          (let [groups (balanced-chunks n (words (:lines c)))
                g      (count groups)
                lens   (mapv (fn [grp] (reduce + 0 (map count grp))) groups)
                tot    (max 1 (reduce + 0 lens))
                start  (:start-ms c)
                end    (:end-ms c)
                span   (- end start)
                bounds (vec (reductions + 0 lens))
                raw    (mapv (fn [cum] (+ start (Math/round (* span (/ (double cum) tot)))))
                            bounds)
                mono   (reduce (fn [acc i]
                                 (let [prev   (peek acc)
                                       lo     (if prev (inc prev) (nth raw 0))
                                       capped (min (max (nth raw i) lo) (- end (- g i)))]
                                   (conj acc capped)))
                               []
                               (range (inc g)))]
            (mapv (fn [i]
                    {:index   (:index c)
                     :start-ms (nth mono i)
                     :end-ms   (nth mono (inc i))
                     :lines   (piece-lines max-chars (nth groups i))})
                  (range g))))))))

(defn- split-cues [max-cps max-chars max-lines cue-maps]
  (vec (mapcat #(split-cue max-cps max-chars max-lines %) cue-maps)))

;; --- rule: snap boundaries to a grid ---------------------------------------

(defn- snap-ms [snap ms]
  (* snap (Math/round (/ (double ms) snap))))

(defn- snap-cues
  "Round each cue's start/end to the nearest `snap`-ms grid, keeping end > start."
  [snap cue-maps]
  (mapv (fn [c]
          (let [s (snap-ms snap (:start-ms c))
                e (snap-ms snap (:end-ms c))]
            (assoc c :start-ms s :end-ms (if (> e s) e (+ s snap)))))
        cue-maps))

;; --- re-index (structural, always last) ------------------------------------

(defn- reindex [cue-maps]
  (vec (map-indexed (fn [i c] (assoc c :index (inc i)))
                    (sort-by (juxt :start-ms :end-ms) cue-maps))))

;; --- public entry ----------------------------------------------------------

(defn reflow
  "Cutting phase B — shape parsed cue-maps by a data-driven rule table. `rules` =
   {:drop-music? bool, :max-dur-ms ms, :min-dur-ms ms, :min-gap-ms ms,
    :max-chars-line n, :max-lines n, :max-cps n, :snap ms} — every key optional;
    an absent key disables its rule. Overlap-merge and 1-based re-index always
    run. Returns cue-maps re-numbered 1-based in time order.
   Pure + total: [cue-map] -> [cue-map]."
  [cue-maps {:keys [drop-music? max-dur-ms min-dur-ms min-gap-ms
                    max-chars-line max-lines max-cps snap]}]
  (let [steps (cond-> []
                drop-music?    (conj drop-music)
                :always        (conj merge-overlaps)
                max-dur-ms     (conj #(cap-duration max-dur-ms %))
                min-dur-ms     (conj #(extend-duration min-dur-ms min-gap-ms %))
                min-gap-ms     (conj #(enforce-gap min-gap-ms %))
                max-chars-line (conj #(wrap-cues max-chars-line %))
                max-cps        (conj #(split-cues max-cps max-chars-line max-lines %))
                snap           (conj #(snap-cues snap %)))]
    (reindex (reduce (fn [cs step] (step cs)) (vec cue-maps) steps))))
