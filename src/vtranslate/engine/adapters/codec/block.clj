(ns vtranslate.engine.adapters.codec.block
  "Shared SRT/WebVTT block skeleton (pure, no IO). Both codecs render one Cue as
   `index\\n<start> --> <end>\\n<lines>` differing only in the fractional-seconds
   separator, and normalize + split a document into cue blocks identically."
  (:require [clojure.string :as str]
            [vtranslate.engine.adapters.codec.timecode :as tc]))

(defn cue->block
  "One domain Cue -> a subtitle block string (no trailing blank line). `sep` is the
   fractional-seconds separator (\",\" for SRT, \".\" for WebVTT)."
  [sep {:keys [index range lines]}]
  (str index "\n"
       (tc/ms->clock (get-in range [:start :ms]) sep) " --> "
       (tc/ms->clock (get-in range [:end :ms]) sep) "\n"
       (str/join "\n" lines)))

(defn split-blocks
  "Normalize CRLF + strip a leading BOM, trim, then split into non-empty blocks."
  [text]
  (-> text
      (str/replace "\r\n" "\n")
      (str/replace "﻿" "")
      str/trim
      (str/split #"\n[ \t]*\n")))
