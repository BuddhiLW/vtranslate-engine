(ns vtranslate.engine.calc.reflow.linebreak
  "Promote (CPPB), pure: optimal line breaking for one caption.

   The greedy wrapper fills a line until the next word does not fit, which
   leaves a full line over a stub and breaks wherever the width runs out. This
   one chooses every break at once, by dynamic programming over the words, and
   minimises a cost declared as data:

     balance   lines of similar length (the sum of squared fill ratios, which
               for a fixed line count is smallest when the lines are even)
     lines     one more line is worse than any imbalance, so text that fits on
               one line stays on one line
     weak      a line must not end on a word that belongs to the next one: an
               article, a preposition, a conjunction (a table per language)
     punct     a break right after punctuation is a good break
     pyramid   a tie goes to the shorter line on top
     overflow  more lines than `:max-lines` is allowed, never silently: it is
               priced above everything else and flagged in the result

   The words are a chain, so the program is exact and O(lines * words^2)."
  (:require [clojure.string :as str]))

(def default-weights
  {:balance 1.0 :lines 1.0 :weak 0.6 :punct 0.25 :pyramid 0.01 :overflow 100.0})

(def weak-words
  "Words that bind to what follows them, by primary language subtag. A line
   ending on one of these reads as a stumble."
  {"en" #{"a" "an" "the" "of" "to" "in" "on" "at" "by" "for" "with" "from" "and"
          "or" "but" "as" "that" "my" "your" "his" "her" "its" "our" "their"}
   "pt" #{"a" "o" "as" "os" "um" "uma" "uns" "umas" "de" "do" "da" "dos" "das"
          "em" "no" "na" "nos" "nas" "por" "para" "pra" "com" "sem" "e" "ou"
          "mas" "que" "se" "ao" "aos" "à" "às" "pelo" "pela" "meu" "minha"
          "seu" "sua" "num" "numa"}
   "es" #{"a" "el" "la" "los" "las" "un" "una" "unos" "unas" "de" "del" "en"
          "por" "para" "con" "sin" "y" "e" "o" "u" "pero" "que" "se" "al" "mi"
          "tu" "su"}
   "fr" #{"à" "le" "la" "les" "un" "une" "des" "de" "du" "en" "dans" "par"
          "pour" "avec" "sans" "et" "ou" "mais" "que" "qui" "au" "aux" "mon"
          "ma" "ton" "ta" "son" "sa"}
   "de" #{"der" "die" "das" "den" "dem" "des" "ein" "eine" "einen" "einem"
          "einer" "in" "im" "an" "am" "auf" "aus" "bei" "mit" "von" "vom" "zu"
          "zum" "zur" "für" "und" "oder" "aber" "dass"}
   "it" #{"a" "il" "lo" "la" "i" "gli" "le" "un" "uno" "una" "di" "del" "della"
          "in" "nel" "nella" "da" "per" "con" "su" "e" "o" "ma" "che" "al"}})

(def ^:private any-language (reduce into #{} (vals weak-words)))

(defn weak-set
  "The weak-word table for a BCP 47 `language` tag; every table at once when
   the language is unknown or absent."
  [language]
  (or (some-> language str str/lower-case (str/split #"[-_]") first weak-words)
      any-language))

(defn- bare [word]
  (str/lower-case (str/replace word #"^[^\p{L}\p{N}]+|[^\p{L}\p{N}]+$" "")))

(defn- ends-with-punct? [word]
  (boolean (re-find #"[,.;:!?…]$" word)))

(defn- hard-split
  "Tokens no line can hold (spaceless CJK, a URL) cut into line-sized pieces."
  [max-chars words]
  (vec (mapcat (fn [w]
                 (if (> (count w) max-chars)
                   (map #(apply str %) (partition-all max-chars w))
                   [w]))
               words)))

(defn- break-cost
  "What it costs to end a line on `word`."
  [{:keys [weak punct]} weak? word]
  (cond (weak? (bare word))     weak
        (ends-with-punct? word) (- punct)
        :else                   0.0))

(defn- solve
  "Best layouts of `words` for every line count up to `limit`, and past it only
   until the first count that can hold them (one word per line always can).
   => vector indexed by line count k of {:cost c :breaks [start-index ...]},
   nil where k lines cannot hold the words."
  [words max-chars limit weights weak?]
  (let [n      (count words)
        lens   (mapv count words)
        prefix (vec (reductions + 0 lens))
        ;; width of words [i, j) on one line, single spaces between
        width  (fn [i j] (+ (- (prefix j) (prefix i)) (dec (- j i))))
        ratio  (fn [i j] (/ (double (width i j)) max-chars))
        inf    Double/POSITIVE_INFINITY]
    (loop [k 1, prev nil, out [nil]]
      (if (or (> k n) (and (> k limit) (some some? out)))
        out
        (let [row (mapv
                   (fn [j]
                     ;; first j words in k lines, the last line being [i, j)
                     (reduce
                      (fn [best i]
                        (let [before (cond (= k 1)  (when (zero? i) {:cost 0.0 :breaks []})
                                           (pos? i) (nth prev i)
                                           :else    nil)]
                          (if (or (nil? before) (> (width i j) max-chars))
                            best
                            (let [r (ratio i j)
                                  c (+ (:cost before)
                                       (* (:balance weights) r r)
                                       ;; earlier lines pay more for their length
                                       (* (:pyramid weights) r (/ 1.0 k))
                                       (if (pos? i)
                                         (break-cost weights weak? (nth words (dec i)))
                                         0.0))]
                              (if (< c (:cost best inf))
                                {:cost c :breaks (conj (:breaks before) i)}
                                best)))))
                      nil
                      (range 0 (max 1 j))))
                   (range 0 (inc n)))]
          (recur (inc k) row (conj out (nth row n))))))))

(defn- ->lines [words breaks]
  (let [bounds (conj (vec breaks) (count words))]
    (mapv (fn [[a b]] (str/join " " (subvec words a b)))
          (partition 2 1 bounds))))

(defn break-lines
  "Lay `words` out on lines of at most `max-chars`.

   `opts` = {:max-lines n, :language tag, :weights {..}}; all optional. With no
   `:max-lines` any number of lines is free of the overflow price.
   => {:lines [s ...] :cost c :overflow? bool}. Blank input gives no lines."
  [words max-chars {:keys [max-lines language weights]}]
  (let [words (hard-split max-chars (vec (remove str/blank? words)))
        n     (count words)]
    (if (zero? n)
      {:lines [] :cost 0.0 :overflow? false}
      (let [weights (merge default-weights weights)
            limit   (or max-lines n)
            layouts (solve words max-chars limit weights (weak-set language))
            priced  (keep (fn [k]
                            (when-let [{:keys [cost breaks]} (nth layouts k)]
                              {:k k :breaks breaks
                               :cost (+ cost
                                        (* (:lines weights) (dec k))
                                        (* (:overflow weights) (max 0 (- k limit))))}))
                          (range 1 (count layouts)))
            best    (reduce (fn [a b] (if (< (:cost b) (:cost a)) b a)) priced)]
        {:lines     (->lines words (:breaks best))
         :cost      (:cost best)
         :overflow? (> (:k best) limit)}))))
