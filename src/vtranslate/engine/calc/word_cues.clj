(ns vtranslate.engine.calc.word-cues
  "Pure: a transcriber reply's segments recut into subtitle-sized segments by
   its word timestamps. How much audio a decoder hears at once (up to 30 s) is
   an ASR decision; how long a subtitle stays on screen is a reading decision.
   A server that times its segments coarsely (speaches 0.9's batched pipeline
   answers one segment per request unless asked otherwise, and a fine-tune
   trained without timestamp tokens answers one segment whatever it is asked)
   would otherwise put the whole window on screen as one cue. The words carry
   their own times, so the cut follows the speech: at a pause, after a
   sentence's end, and before a piece outgrows the policy's duration or length.

   Seconds in, seconds out, same keys as the server's verbose_json: segments
   {:start :end :text ...metrics}, words {:start :end :word}."
  (:require [clojure.string :as str]))

(def default-policy
  "7 s and 84 characters (two 42-character lines) bound a subtitle a viewer can
   read; a 600 ms silence is a phrase boundary; a sentence end closes a piece
   once it has been on screen 1 s."
  {:max-ms 7000 :max-chars 84 :pause-ms 600 :min-ms 1000})

(def ^:private sentence-end #"[.!?؟。！？…]$")

(defn- ms [s] (* 1000.0 (double s)))

(defn- text-of [words]
  (str/trim (apply str (map :word words))))

(defn- owner
  "Index of the segment word `w` belongs to: the first whose end reaches the
   word's midpoint, else the last."
  [segs w]
  (let [mid (/ (+ (double (:start w)) (double (:end w))) 2.0)]
    (or (first (keep-indexed (fn [i s] (when (<= mid (double (:end s))) i)) segs))
        (dec (count segs)))))

(defn- groups
  "`words` in runs no run of which a subtitle should outgrow. => [[word]]"
  [words {:keys [max-ms max-chars pause-ms min-ms]}]
  (reduce (fn [acc w]
            (let [cur  (peek acc)
                  prev (peek cur)]
              (if (or (nil? prev)
                      (let [dur   (- (ms (:end w)) (ms (:start (first cur))))
                            gap   (- (ms (:start w)) (ms (:end prev)))
                            chars (count (text-of (conj cur w)))
                            said  (- (ms (:end prev)) (ms (:start (first cur))))]
                        (not (or (>= gap pause-ms)
                                 (> dur max-ms)
                                 (> chars max-chars)
                                 (and (>= said min-ms)
                                      (re-find sentence-end (str/trim (str (:word prev)))))))))
                (if cur (conj (pop acc) (conj cur w)) (conj acc [w]))
                (conj acc [w]))))
          [] words))

(defn recut
  "`segs` recut by `words` under `policy` (default-policy for any key it does
   not name). Each piece keeps its segment's metrics and takes its words' text
   and times. A reply without words, or a segment no word falls in, is kept as
   it came. => [segment]"
  ([segs words] (recut segs words nil))
  ([segs words policy]
   (let [policy (merge default-policy policy)
         segs   (vec segs)
         words  (filter #(and (number? (:start %)) (number? (:end %)) (not (str/blank? (:word %)))) words)]
     (if (or (empty? segs) (empty? words))
       segs
       (let [by-seg (group-by #(owner segs %) (sort-by :start words))]
         (into []
               (mapcat (fn [i s]
                         (if-let [ws (seq (get by-seg i))]
                           (let [base (dissoc s :start :end :text :words :tokens :id :seek)]
                             (map (fn [g] (assoc base :start (:start (first g)) :end (:end (peek g))
                                                      :text (text-of g)))
                                  (groups ws policy)))
                           [s]))
                       (range) segs)))))))
