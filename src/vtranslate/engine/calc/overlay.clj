(ns vtranslate.engine.calc.overlay
  "Pure subtitle-overlay calc for the hardsub composer: project a rendered
   SubtitleTrack into a time-ordered timeline and, for a frame timestamp, pick the
   active lines to draw. No IO, no AWT — the Collect boundary owns the pixels."
  (:require [clojure.string :as string]))

(defn timeline
  "Project a SubtitleTrack's Cues into a time-ordered vector of plain overlay cues
   {:start-ms long :end-ms long :lines [str ...]}."
  [track]
  (->> (:cues track)
       (map (fn [{:keys [range lines]}]
              {:start-ms (get-in range [:start :ms])
               :end-ms   (get-in range [:end :ms])
               :lines    (vec lines)}))
       (sort-by :start-ms)
       vec))

(defn active-lines
  "Lines to display at `t-ms` (inclusive start, exclusive end), or nil when no cue
   covers it. First covering cue wins (display cues are time-disjoint)."
  [tl t-ms]
  (some (fn [{:keys [start-ms end-ms lines]}]
          (when (and (<= start-ms t-ms) (< t-ms end-ms)) lines))
        tl))

(defn wrap-line
  "Greedy word-wrap `text` to <= `max-chars` per line => vector of lines. A nil or
   non-positive `max-chars` leaves the text on one line."
  [text max-chars]
  (if (or (nil? max-chars) (not (pos? max-chars)) (<= (count text) max-chars))
    [text]
    (reduce (fn [lines word]
              (let [cur (peek lines)]
                (if (and cur (<= (+ (count cur) 1 (count word)) max-chars))
                  (conj (pop lines) (str cur " " word))
                  (conj lines word))))
            []
            (remove empty? (string/split (string/trim text) #"\s+")))))
