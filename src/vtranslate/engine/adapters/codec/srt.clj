(ns vtranslate.engine.adapters.codec.srt
  "SubRip (SRT) codec adapter — implements the subtitle ports as a pure
   text<->cue transform (no IO). Block form:

     1
     00:00:01,000 --> 00:00:04,000
     line one
     line two
     <blank>

   `render-bytes` walks a domain SubtitleTrack; `parse` promotes wire text into
   boundary cue maps ({:index :start-ms :end-ms :lines}) — the no-ASR ingress (M6)."
  (:require [clojure.string :as str]
            [hive-dsl.result :as r]
            [vtranslate.engine.adapters.codec.timecode :as tc]
            [vtranslate.engine.port.subtitle :as p.sub]))

(def ^:private sep ",")

;; --- render: SubtitleTrack -> SRT text --------------------------------------

(defn- cue->block
  "One domain Cue -> an SRT block string (no trailing blank line)."
  [{:keys [index range lines]}]
  (str index "\n"
       (tc/ms->clock (get-in range [:start :ms]) sep) " --> "
       (tc/ms->clock (get-in range [:end :ms]) sep) "\n"
       (str/join "\n" lines)))

(defn- render-track
  "Domain SubtitleTrack -> SRT document string (blank-line separated, EOL-terminated)."
  [track]
  (str (str/join "\n\n" (map cue->block (:cues track))) "\n"))

;; --- parse: SRT text -> cue maps --------------------------------------------

(defn- split-blocks
  "Normalize CRLF + BOM, then split into non-empty cue blocks."
  [text]
  (-> text
      (str/replace "\r\n" "\n")
      (str/replace "﻿" "")
      str/trim
      (str/split #"\n[ \t]*\n")))

(defn- timecodes
  "\"<start> --> <end>\" -> [start-ms end-ms], or nil if either side is not a clock."
  [time-line]
  (let [[start end] (map tc/clock->ms (str/split time-line #"-->"))]
    (when (and start end) [start end])))

(defn- block->cue
  "One SRT block -> {:index :start-ms :end-ms :lines}, or nil when malformed."
  [block]
  (let [[idx-line time-line & text-lines] (str/split-lines block)]
    (when-let [[start end] (some-> time-line timecodes)]
      {:index   (or (parse-long (str/trim idx-line)) 0)
       :start-ms start
       :end-ms   end
       :lines    (vec text-lines)})))

(defn- parse-text
  "SRT document string -> {:cues [...]} (malformed blocks dropped)."
  [text]
  {:cues (into [] (keep block->cue) (split-blocks text))})

;; --- adapter ----------------------------------------------------------------

(defrecord SrtCodec []
  p.sub/ISubtitleRenderer
  (render-bytes [_ track]
    (r/ok (render-track track)))

  p.sub/ISubtitleParser
  (parse [_ text format]
    (if (= format :format/srt)
      (r/ok (parse-text text))
      (r/err :error/unsupported-format {:format format}))))

(defn make-codec
  "Construct the SRT codec (satisfies ISubtitleRenderer + ISubtitleParser)."
  []
  (->SrtCodec))
