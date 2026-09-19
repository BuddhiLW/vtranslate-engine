(ns vtranslate.engine.calc.reflow.optimal
  "Promote (CPPB), pure: the `:optimal` reflow strategy. Shapes a whole track at
   once instead of one rule after another.

   The greedy strategy decides each thing locally and once: wrap this cue,
   extend that one up to its neighbour. Nothing weighs the sequence, so a
   caption blinks off for 300 ms between two phrases, or three fragments of one
   sentence flash by as three cues. Here every decision is a choice with a
   price, and the track that costs least wins:

     per cue    where its lines break (reflow.linebreak), when it starts (a
                bounded lead-in before the speech) and when it ends (as spoken,
                held to a readable length, or held until the next cue)
     per pair   whether neighbours merge into one caption

   Hard limits, never traded: cues stay in order and never overlap, no words
   are lost or reordered, a caption is never held past `:max-dur-ms`, and a
   merge never spans a pause longer than `:merge-gap-ms`.

   Priced (see `default-weights`): reading speed above the target, captions
   shorter than `:min-dur-ms`, a blank flash between close cues, holding text
   long after it was read, showing text before it is spoken, joining across a
   sentence end, and the line layout's own cost.

   Cues are a chain: a cue's choices only meet its neighbours'. That makes the
   cheapest track a shortest path, found exactly by dynamic programming in
   O(cues), where a constraint solver would search. The rules stay declarative
   (limits and weights are data) and the answer is optimal, not merely valid.

   `:style :roll-up` afterwards carries the previous caption's last line on
   top of a cue that follows it closely, for a continuous read."
  (:require [clojure.string :as str]
            [vtranslate.engine.calc.reflow :as reflow]
            [vtranslate.engine.calc.reflow.linebreak :as linebreak]))

(def defaults
  {:max-chars-line 42
   :max-lines      2
   :target-cps     17
   :min-dur-ms     800
   :max-dur-ms     7000
   :min-gap-ms     80
   ;; a blank shorter than this between two cues reads as a flicker
   :close-gap-ms   1000
   ;; how far before the speech a caption may appear; 0 never moves a start
   :max-lead-ms    0
   ;; at most this many source cues in one caption, none across a longer pause
   :max-group      3
   :merge-gap-ms   300})

(def default-weights
  {:layout         1.0   ; x the line breaker's cost
   :cps            0.5   ; x (chars/s over target)^2
   :short          1.0   ; per 100 ms under :min-dur-ms
   :linger         0.4   ; per second held after both speech and reading ended
   :lead           0.5   ; per 100 ms shown before the speech
   :early          1.0   ; per second a merged cue's text shows before its speech
   :merge          0.8   ; per join
   :merge-sentence 3.0   ; more, when the join crosses a sentence end
   :flash          2.0   ; a blank shorter than :close-gap-ms
   :gap-short      5.0}) ; per 10 ms under :min-gap-ms

(defn- params [rules]
  (let [given (into {} (remove (comp nil? val)) rules)]
    (-> (merge defaults given)
        (assoc :target-cps (or (:max-cps given) (:target-cps given) (:target-cps defaults))
               :weights    (merge default-weights (:weights given))))))

;; --- one caption: a run of source cues --------------------------------------

(defn- dialogue?
  "Two speakers on their own dashed lines. Such a cue keeps its lines."
  [{:keys [lines]}]
  (and (> (count lines) 1)
       (boolean (some #(re-find #"^\s*[-–]\s" %) lines))))

(defn- sentence-end? [{:keys [lines]}]
  (boolean (re-find #"[.!?…][\"')\]]*\s*$" (or (last lines) ""))))

(defn- fits? [lines {:keys [max-chars-line max-lines]}]
  (and (<= (count lines) max-lines)
       (every? #(<= (count %) max-chars-line) lines)))

(defn- layout
  "=> {:lines [...] :cost c :overflow? bool} for the cues shown as one caption."
  [cues {:keys [max-chars-line max-lines language weights] :as p}]
  (if (and (= 1 (count cues)) (dialogue? (first cues)) (fits? (:lines (first cues)) p))
    {:lines (:lines (first cues)) :cost 0.0 :overflow? false}
    (linebreak/break-lines (reflow/words (mapcat :lines cues)) max-chars-line
                           {:max-lines max-lines :language language
                            :weights (:linebreak weights)})))

(defn- group
  "The caption made of `cues` (consecutive, in order), or nil when they may not
   be shown together. A single cue is always allowed, and one with no words to
   lay out keeps the lines it came with."
  [cues {:keys [merge-gap-ms max-dur-ms weights] :as p}]
  (let [s0    (:start-ms (first cues))
        e0    (:end-ms (peek cues))
        joins (partition 2 1 cues)
        solo? (= 1 (count cues))]
    (when (or solo?
              (and (not-any? dialogue? cues)
                   (<= (- e0 s0) max-dur-ms)
                   (every? (fn [[a b]] (<= (- (:start-ms b) (:end-ms a)) merge-gap-ms)) joins)))
      (let [{:keys [lines cost overflow?]} (layout cues p)
            lines (if (and solo? (empty? lines)) (vec (:lines (first cues))) lines)]
        (when (or solo? (and (seq lines) (not overflow?)))
          {:s0    s0
           :e0    e0
           :lines lines
           :chars (reduce + 0 (map count lines))
           :cost  (+ (* (:layout weights) cost)
                     (* (:merge weights) (count joins))
                     (* (:merge-sentence weights)
                        (count (filter (comp sentence-end? first) joins)))
                     (* (:early weights)
                        (/ (reduce + 0 (map #(- (:start-ms %) s0) (rest cues))) 1000.0)))})))))

;; --- its timing choices -----------------------------------------------------

(defn- timing-cost
  [{:keys [s0 e0 chars]} s e {:keys [target-cps min-dur-ms weights]}]
  (let [dur     (max 1 (- e s))
        over    (max 0.0 (- (/ (* chars 1000.0) dur) target-cps))
        read-ms (long (Math/ceil (/ (* chars 1000.0) target-cps)))]
    (+ (* (:cps weights) over over)
       (* (:short weights) (/ (max 0 (- min-dur-ms dur)) 100.0))
       (* (:linger weights) (/ (max 0 (- e (max e0 (+ s read-ms)))) 1000.0))
       (* (:lead weights) (/ (- s0 s) 100.0)))))

(defn- candidates
  "Every [start end] worth considering for caption `g`, the next source cue
   starting at `next0` (nil for the last). Ends never precede the speech's end
   unless the next cue or `:max-dur-ms` forces it, as the greedy rules do."
  [{:keys [s0 e0 chars] :as g} next0
   {:keys [target-cps min-dur-ms max-dur-ms min-gap-ms close-gap-ms max-lead-ms] :as p}]
  (let [read-ms (long (Math/ceil (/ (* chars 1000.0) target-cps)))
        starts  (distinct [s0 (max 0 (- s0 max-lead-ms))])]
    (vec
     (for [s starts
           :let [ceiling (min (+ s max-dur-ms)
                              (if next0 (max s (- next0 min-gap-ms)) Long/MAX_VALUE))
                 wants   (cond-> [e0 (+ s min-dur-ms) (+ s read-ms)]
                           (and next0 (< (- next0 e0) close-gap-ms))
                           (conj (- next0 min-gap-ms) (- next0 max-lead-ms min-gap-ms)))]
           e (distinct (map #(max s (min ceiling (max e0 %))) wants))]
       {:s s :e e :cost (+ (:cost g) (timing-cost g s e p))}))))

(defn- edge-cost
  "What the blank between a caption ending at `e` and the next starting at `s`
   costs; nil when they would overlap."
  [e s {:keys [min-gap-ms close-gap-ms weights]}]
  (let [gap (- s e)]
    (cond (neg? gap)           nil
          (< gap min-gap-ms)   (* (:gap-short weights) (/ (- min-gap-ms gap) 10.0))
          (= gap min-gap-ms)   0.0
          (< gap close-gap-ms) (:flash weights)
          :else                0.0)))

;; --- the cheapest track -----------------------------------------------------

(defn- cheapest-into
  "The best way to reach choice `c` from the nodes `preds` (nil at the track's
   start). => node | nil when every predecessor overlaps it."
  [preds c g p]
  (if (nil? preds)
    (assoc c :g g :total (:cost c) :prev nil)
    (reduce (fn [best pred]
              (if-let [edge (edge-cost (:e pred) (:s c) p)]
                (let [total (+ (:total pred) edge (:cost c))]
                  (if (or (nil? best) (< total (:total best)))
                    (assoc c :g g :total total :prev pred)
                    best))
                best))
            nil
            preds)))

(defn solve
  "The cheapest captions for `cues` (time-ordered, non-overlapping cue-maps).
   => {:cues [cue-map ...] :cost c}; indices are left for the caller."
  [cues rules]
  (let [p    (params rules)
        cues (vec cues)
        n    (count cues)]
    (if (zero? n)
      {:cues [] :cost 0.0}
      (let [ends-at
            (reduce
             (fn [ends-at i]
               (let [preds (when (pos? i) (nth ends-at (dec i)))]
                 (reduce
                  (fn [ends-at j]
                    (if-let [g (group (subvec cues i (inc j)) p)]
                      (let [next0 (:start-ms (get cues (inc j)))
                            nodes (keep #(cheapest-into preds % g p) (candidates g next0 p))]
                        (update ends-at j into nodes))
                      ends-at))
                  ends-at
                  (range i (min n (+ i (:max-group p)))))))
             (vec (repeat n []))
             (range n))
            best (apply min-key :total (nth ends-at (dec n)))]
        {:cost (:total best)
         :cues (->> (iterate :prev best)
                    (take-while some?)
                    reverse
                    (mapv (fn [{:keys [s e g]}]
                            {:index 0 :start-ms s :end-ms e :lines (:lines g)})))}))))

;; --- roll-up ----------------------------------------------------------------

(defn- roll-up
  "Carry the previous caption's last line on top of each cue that follows it
   within `:close-gap-ms` and has a line to spare."
  [cues {:keys [max-lines close-gap-ms]}]
  (vec (map-indexed
        (fn [i c]
          (let [prev (get cues (dec i))]
            (if (and prev
                     (< (count (:lines c)) max-lines)
                     (< (- (:start-ms c) (:end-ms prev)) close-gap-ms))
              (update c :lines #(into [(peek (:lines prev))] %))
              c)))
        cues)))

;; --- the strategy -----------------------------------------------------------

(defmethod reflow/shape :optimal
  [cue-maps {:keys [drop-music? max-cps snap style] :as rules}]
  (let [{:keys [max-chars-line max-lines] :as p} (params rules)]
    (cond->> (vec cue-maps)
      drop-music?         (reflow/drop-music)
      :always             (reflow/merge-overlaps)
      ;; a cue too dense for any timing is still split over its own span
      max-cps             (reflow/split-cues max-cps max-chars-line max-lines)
      :always             (#(:cues (solve % rules)))
      (= :roll-up (some-> style name keyword)) (#(roll-up % p))
      snap                (reflow/snap-cues snap)
      :always             (reflow/reindex))))
