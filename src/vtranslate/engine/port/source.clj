(ns vtranslate.engine.port.source
  "Port (ISP barrier) for reading a subtitle/text source into memory — the
   boundary read that feeds the no-ASR ingress (M6). The engine pipeline depends
   on THIS protocol, never on a concrete reader; a driven adapter (filesystem
   now, URL/stdin later) implements it. Kept separate from the media port:
   subtitle ingress needs raw text, not a demuxed audio track, so a consumer asks
   for the smallest sufficient interface (ISP).")

(defprotocol ISourceReader
  "Read a source locator into its full text content."
  (read-text [this source-uri]
    "=> (r/ok text-string) | (r/err :error/source-unreadable {:source-uri s})."))
