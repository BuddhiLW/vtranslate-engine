(ns vtranslate.engine.adapters.codec.vtt
  "WebVTT codec adapter — pure text<->cue transform (no IO). Document form:

     WEBVTT
     <blank>
     1
     00:00:01.000 --> 00:00:04.000
     line one
     <blank>

   Differs from SRT: a 'WEBVTT' header, '.' fractional separator, OPTIONAL cue
   identifiers, and optional cue settings after the end timestamp (ignored on
   parse). NOTE / STYLE / REGION blocks are skipped."
  (:require [clojure.string :as str]
            [hive-dsl.result :as r]
            [vtranslate.engine.adapters.codec.timecode :as tc]
            [vtranslate.engine.port.subtitle :as p.sub]))

(def ^:private sep ".")
(def ^:private header "WEBVTT")

;; --- render: SubtitleTrack -> WebVTT text ------------------------------------

(defn- cue->block
  "One domain Cue -> a WebVTT cue block (identifier line + timing + text)."
  [{:keys [index range lines]}]
  (str index "\n"
       (tc/ms->clock (get-in range [:start :ms]) sep) " --> "
       (tc/ms->clock (get-in range [:end :ms]) sep) "\n"
       (str/join "\n" lines)))

(defn- render-track
  "Domain SubtitleTrack -> WebVTT document string (header + blank-separated cues)."
  [track]
  (str header "\n\n"
       (str/join "\n\n" (map cue->block (:cues track))) "\n"))

;; --- parse: WebVTT text -> cue maps -----------------------------------------

(defn- split-blocks
  "Normalize CRLF + BOM, then split into non-empty blocks."
  [text]
  (-> text
      (str/replace "\r\n" "\n")
      (str/replace "﻿" "")
      str/trim
      (str/split #"\n[ \t]*\n")))

(defn- cue-block?
  "True unless the block is the WEBVTT header or a NOTE/STYLE/REGION block."
  [block]
  (not (or (str/starts-with? block header)
           (re-find #"^(NOTE|STYLE|REGION)\b" block))))

(defn- timing-index
  "Index of the first '-->' timing line in a cue block's lines, or nil."
  [lines]
  (first (keep-indexed (fn [i l] (when (str/includes? l "-->") i)) lines)))

(defn- timecodes
  "Cue-timing line -> [start-ms end-ms]; tolerates trailing cue settings."
  [line]
  (let [[start-tok end-tok] (str/split line #"-->")
        start (tc/clock->ms start-tok)
        end   (some-> end-tok str/trim (str/split #"\s+") first tc/clock->ms)]
    (when (and start end) [start end])))

(defn- cue-index
  "Optional identifier line (the line before the timing line) as an int, else 0."
  [lines timing-i]
  (or (when (pos? timing-i) (some-> (nth lines (dec timing-i)) str/trim parse-long))
      0))

(defn- block->cue
  "A WebVTT cue block -> {:index :start-ms :end-ms :lines}, or nil if not a cue."
  [block]
  (let [lines (str/split-lines block)
        i     (timing-index lines)]
    (when-let [[start end] (when i (timecodes (nth lines i)))]
      {:index    (cue-index lines i)
       :start-ms start
       :end-ms   end
       :lines    (vec (drop (inc i) lines))})))

(defn- parse-text
  "WebVTT document string -> {:cues [...]} (header/metadata/malformed dropped)."
  [text]
  {:cues (into [] (comp (filter cue-block?) (keep block->cue)) (split-blocks text))})

;; --- adapter ----------------------------------------------------------------

(defrecord WebVttCodec []
  p.sub/ISubtitleRenderer
  (render-bytes [_ track]
    (r/ok (render-track track)))

  p.sub/ISubtitleParser
  (parse [_ text format]
    (if (= format :format/vtt)
      (r/ok (parse-text text))
      (r/err :error/unsupported-format {:format format}))))

(defn make-codec
  "Construct the WebVTT codec (satisfies ISubtitleRenderer + ISubtitleParser)."
  []
  (->WebVttCodec))
