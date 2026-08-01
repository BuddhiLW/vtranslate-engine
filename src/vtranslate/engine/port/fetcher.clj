(ns vtranslate.engine.port.fetcher
  "Port (DIP) for turning a remote locator into a file on disk.

   Everything downstream — probe, demux, ASR — takes a path. A job that starts
   from a URL therefore needs one step ahead of ingest that resolves it, and the
   thing that does the resolving is a COLLABORATOR the boundary is given, never a
   scraper the engine names. The engine ships no adapter; an addon supplies one."
  (:require [hive-dsl.result :as r]))

(defprotocol ISourceFetcher
  "Resolve a remote source locator to a local media file."
  (fetch-media [this uri opts]
    "Fetch `uri` into a local file. `opts` may carry :dir (where to write) and
     :format-hint. Blocks until the media has landed.
     => (r/ok {:local-path str :title str? :metadata map?})
      | (r/err :error/fetch-* {...})."))

(defn fetcher?
  "True when `x` satisfies the port."
  [x]
  (satisfies? ISourceFetcher x))

(defrecord UnavailableFetcher [reason]
  ISourceFetcher
  (fetch-media [_ uri _]
    (r/err :error/no-source-fetcher {:reason reason :source uri})))

(def unavailable
  "A fetcher that refuses every URL. The correct default when no fetch addon is
   loaded — a remote job fails loud instead of reaching probe with a URL it
   cannot open. A record, not a reify: `satisfies?` does not recognise a reify'd
   implementation under babashka."
  (->UnavailableFetcher
   "no source fetcher is configured — load a fetch addon (e.g. :vtranslate/fetch) to translate from a URL"))

(defn checked-fetcher
  "Smart ctor: `x` if it satisfies the port.
   => (r/ok fetcher) | (r/err :error/invalid-source-fetcher {...})."
  [x]
  (if (fetcher? x)
    (r/ok x)
    (r/err :error/invalid-source-fetcher
           {:reason "value does not satisfy ISourceFetcher" :type (str (type x))})))
