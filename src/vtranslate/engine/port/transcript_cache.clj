(ns vtranslate.engine.port.transcript-cache
  "Port (DIP) for keeping the one expensive artefact a job produces.

   ASR dominates the cost of a run — minutes, against seconds for everything
   downstream — so a failure after it (a lapsed API key, a bad mux) must not
   throw that work away. Where the transcript is kept, and in what format, is a
   COLLABORATOR; the pipeline only asks."
  (:require [hive-dsl.result :as r]))

(defprotocol ITranscriptCache
  "Persist and recall a finished transcript by content key."
  (fetch [this key]
    "=> (r/ok transcript) on a hit, (r/ok nil) on a miss, (r/err ...) if the
     store itself is broken.")
  (store! [this key transcript]
    "Persist `transcript` under `key`. => (r/ok key) | (r/err ...).")
  (forget! [this key]
    "Drop one entry. Absent is success. => (r/ok key) | (r/err ...).")
  (evict! [this older-than-seconds]
    "Drop every entry untouched for longer than `older-than-seconds`.
     A non-positive age evicts nothing. => (r/ok removed-count) | (r/err ...)."))

(defn cache?
  "True when `x` satisfies the port."
  [x]
  (satisfies? ITranscriptCache x))

(defrecord NoCache []
  ITranscriptCache
  (fetch [_ _] (r/ok nil))
  (store! [_ key _] (r/ok key))
  (forget! [_ key] (r/ok key))
  (evict! [_ _] (r/ok 0)))

(def disabled
  "A cache that never hits and never stores — the correct default when caching is
   switched off, so the pipeline needs no nil checks."
  (->NoCache))
