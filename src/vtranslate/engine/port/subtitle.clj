(ns vtranslate.engine.port.subtitle
  "Ports (ISP barriers) for subtitle serialization. Split by direction so a
   consumer asks for the smallest sufficient interface: ISubtitleRenderer writes
   a domain SubtitleTrack to wire text; ISubtitleParser reads wire text back into
   cue data (the no-ASR ingress, M6). Adapters (M3) implement each.")

(defprotocol ISubtitleRenderer
  "Serialize a domain SubtitleTrack aggregate to its on-wire text form."
  (render-bytes [this subtitle-track]
    "=> (r/ok wire-text-string) | (r/err :error/render-failed {:reason s})."))

(defprotocol ISubtitleParser
  "Parse on-wire subtitle text into boundary cue data (promoted by calc later)."
  (parse [this text format]
    "=> (r/ok {:cues [{:index n :start-ms n :end-ms n :lines [s]} ...]})
        | (r/err :error/unsupported-format {:format kw})."))
