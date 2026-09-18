(ns vtranslate.engine.calc.asr-hygiene
  "Pure: collapse decoder loops in raw ASR segments without losing speech, and
   recognise decoder PLACEHOLDERS — annotation such as \"(speaking foreign
   language)\" or \"[Music]\" that stands where speech was not transcribed.
   A loop is the same words repeated; collapsing keeps the time the words
   covered and says so on the segment. A placeholder is marked, never
   translated as speech, and keeps its text. Nothing is ever deleted."
  (:require [clojure.string :as str]))

(def version
  "Bump when the rule changes; it is part of the transcript cache identity."
  "loop-collapse-1+placeholder-1")

(def defaults
  "Default values for the collapse heuristics."
  {:min-repeats 3 :compression-ratio-thr 2.4})

(defn normalize-text
  "Lower-case, replace every run of characters that are not letters or digits
   (Unicode aware: \\p{L} and \\p{N}) by one space, trim. nil -> \"\"."
  [s]
  (let [s (str s)]
    (-> s
        str/lower-case
        (str/replace #"[^\p{L}\p{N}]+" " ")
        str/trim)))

(defn- tokenize
  "Split `s` on whitespace into a vector of tokens."
  [s]
  (vec (str/split (str s) #"\s+")))

(defn collapse-runs
  "Collapse consecutive segments whose `normalize-text` is equal AND non-empty
   into runs. A run of length >= (:min-repeats opts) becomes ONE segment:
   every key of the FIRST segment, :end of the LAST, and :asr/loop-collapsed =
   the run length. Shorter runs pass through unchanged. Order preserved.
   Returns a vector.
   Blank segments (normalize-text -> \"\") never form runs — each is a
   partition boundary that breaks any preceding or following run."
  [segments opts]
  (let [min-repeats (or (:min-repeats opts) (:min-repeats defaults))]
    (->> segments
         (partition-by (fn [seg]
                         (let [nt (normalize-text (:text seg))]
                           (when (seq nt) nt))))
         (mapcat (fn [run]
                   (let [first-nt (normalize-text (:text (first run)))]
                     (if (and (seq first-nt)
                              (>= (count run) min-repeats))
                       [(assoc (first run)
                               :end (:end (last run))
                               :asr/loop-collapsed (count run))]
                       run))))
         vec)))

(defn collapse-inner-repetition
  "If the segment's :compression_ratio (or :compression-ratio) is a number >
   (:compression-ratio-thr opts), tokenize the normalized text by spaces; find
   the SMALLEST period p (1 <= p <= n/2) such that the token sequence equals
   its first p tokens repeated (a trailing partial repetition is allowed), and
   the number of whole repetitions (quot n p) is >= (:min-repeats opts). When
   found, replace :text with the first p WORDS of the ORIGINAL text (split on
   whitespace) and set :asr/loop-collapsed to (quot n p). Otherwise return the
   segment unchanged. Timestamps never change."
  [segment opts]
  (let [cr (double (or (:compression_ratio segment)
                       (:compression-ratio segment)
                       0))
        thr (double (or (:compression-ratio-thr opts)
                        (:compression-ratio-thr defaults)))]
    (if (<= cr thr)
      segment
      (let [nt (normalize-text (:text segment))
            tokens (tokenize nt)
            n (count tokens)
            min-reps (or (:min-repeats opts) (:min-repeats defaults))]
        (if (< n 2)
          segment
          (let [period (loop [p 1]
                         (when (<= p (quot n 2))
                           (let [first-p (take p tokens)
                                 whole-reps (quot n p)]
                             (if (and (>= whole-reps min-reps)
                                      (every? (fn [i]
                                                (= (nth tokens i)
                                                   (nth tokens (mod i p))))
                                              (range n)))
                               p
                               (recur (inc p))))))]
            (if period
              (let [original-words (tokenize (:text segment))
                    p-words (take period original-words)
                    whole-reps (quot n period)]
                (assoc segment
                       :text (str/join " " p-words)
                       :asr/loop-collapsed whole-reps))
              segment)))))))

(defn clean
  "Apply `collapse-inner-repetition` to each segment, then `collapse-runs` on
   the result. Returns a vector of segments with loop artifacts collapsed."
  [segments opts]
  (collapse-runs (mapv #(collapse-inner-repetition % opts) segments) opts))

(def language-names
  "Language names an ASR placeholder may carry -> the registry tag they name."
  {"english" "en" "german" "de" "portuguese" "pt" "brazilian" "pt" "spanish" "es"
   "french" "fr" "russian" "ru" "ukrainian" "uk" "chinese" "zh" "mandarin" "zh"
   "cantonese" "zh" "japanese" "ja" "arabic" "ar" "hebrew" "he" "persian" "fa"
   "farsi" "fa" "italian" "it" "korean" "ko" "turkish" "tr" "polish" "pl"
   "dutch" "nl" "hindi" "hi" "indonesian" "id"})

(def ^:private sound-words
  "Words that make a short annotation a SOUND description rather than speech,
   in the languages whisper writes its annotations in."
  #{"music" "applause" "laughter" "laughs" "laughing" "noise" "silence"
    "blank" "static" "cheering" "cheers" "inaudible" "unintelligible"
    "clapping" "sighs" "coughs" "indistinct" "chatter" "musik" "applaus"
    "lachen" "lacht" "gelächter" "beifall" "música" "musica" "aplausos"
    "risos" "risas" "musique" "rires" "silêncio" "silencio"})

(def ^:private foreign-words
  #{"foreign" "language" "languages" "speaking" "speaks" "spoken"})

(defn- annotation-body
  "The inner words of `text` when ALL of it is bracketed/parenthesised/asterisked
   annotation, e.g. \"[speaking German]\" -> [\"speaking\" \"german\"]; else nil."
  [text]
  (let [t (str/trim (str text))]
    (when (re-matches #"(?s)(?:\s*(?:\[[^\[\]]*\]|\([^()]*\)|\*[^*]*\*)\s*)+" t)
      (vec (re-seq #"\p{L}+" (str/lower-case t))))))

(def max-sound-words
  "Longest annotation, in words, still read as a sound description."
  4)

(defn classify-placeholder
  "What kind of ASR placeholder `text` is, or nil when it is speech.
   A placeholder is text made ENTIRELY of annotation (brackets, parentheses,
   asterisks) or of music notes.
   => nil
    | {:kind :foreign-speech :language-hint tag-or-nil}  — speech the decoder
        heard but did not transcribe: \"(speaking foreign language)\",
        \"[speaking German]\", \"[in Spanish]\"
    | {:kind :sound}  — an annotation of at most `max-sound-words` words naming a
        sound: \"[Music]\", \"(applause)\", \"(Alle lachen)\", \"♪♪\", \"[BLANK_AUDIO]\""
  [text]
  (let [t (str/trim (str text))]
    (if (and (seq t) (re-matches #"[\s♪♫#~.]+" t) (re-find #"[♪♫]" t))
      {:kind :sound}
      (when-let [words (annotation-body t)]
        (let [named (some language-names words)]
          (cond
            (or named (some foreign-words words))
            {:kind :foreign-speech :language-hint named}

            (and (some sound-words words) (<= (count words) max-sound-words))
            {:kind :sound}))))))

(defn mark-placeholder
  "`segment` carrying :asr/placeholder (the `classify-placeholder` kind) and, for
   foreign speech that names its language, :asr/language-hint. The text is kept
   verbatim; a speech segment comes back unchanged."
  [segment]
  (if-let [{:keys [kind language-hint]} (classify-placeholder (:text segment))]
    (cond-> (assoc segment :asr/placeholder kind)
      language-hint (assoc :asr/language-hint language-hint))
    segment))

(defn placeholder?
  "True when `segment` is a decoder placeholder rather than speech: it carries an
   :asr/placeholder mark, or its text classifies as one."
  [segment]
  (boolean (or (:asr/placeholder segment)
               (classify-placeholder (:text segment)))))

(defn placeholder-count
  "How many of `segments` are placeholders of `kind` (any kind when nil)."
  ([segments] (placeholder-count segments nil))
  ([segments kind]
   (count (filter (fn [s]
                    (when-let [c (classify-placeholder (:text s))]
                      (or (nil? kind) (= kind (:kind c)))))
                  segments))))
