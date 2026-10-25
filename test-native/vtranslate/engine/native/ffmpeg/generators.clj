(ns vtranslate.engine.native.ffmpeg.generators
  "test.check generators shared by the :ffmpeg native contract suite. Cues sit on
   the fixture's 100 ms frame grid inside its 1 s duration, so every frame has an
   unambiguous cue-active? oracle."
  (:require [clojure.string :as str]
            [clojure.test.check.generators :as gen]
            [vtranslate.engine.native.media-fixture :as fx]))

(def line-gen
  "A non-blank caption line: 1..6 alphanumerics, optionally a second word."
  (gen/let [a (gen/fmap str/join (gen/vector gen/char-alphanumeric 1 6))
            b (gen/fmap str/join (gen/vector gen/char-alphanumeric 0 4))]
    (if (seq b) (str a " " b) a)))

(defn- place
  "Lay [gap len lines] parts end to end on the frame grid, dropping any cue
   that would run past 1000 ms."
  [parts]
  (loop [parts parts, t 0, acc []]
    (if-let [[gap len lines] (first parts)]
      (let [start (+ t (* gap fx/frame-ms))
            end   (+ start (* len fx/frame-ms))]
        (if (<= end 1000)
          (recur (rest parts) end (conj acc {:start-ms start :end-ms end :lines lines}))
          acc))
      acc)))

(def cues-gen
  "1..3 ordered, non-overlapping cues on the frame grid, all ending <= 1000 ms."
  (gen/fmap place
            (gen/vector (gen/tuple (gen/choose 0 3) (gen/choose 1 4)
                                   (gen/vector line-gen 1 2))
                        1 3)))

(def languages
  "BCP-47 tags domain.rendering accepts, with the ISO 639-2 code each must
   reach the container as."
  {"en" "eng" "pt-BR" "por" "zh-CN" "zho" "de" "deu" "ja" "jpn" "es" "spa"
   "fr" "fra" "ar" "ara" "ru" "rus" "he" "heb" "id" "ind" "uk" "ukr"})

(def language-gen (gen/elements (sort (keys languages))))
