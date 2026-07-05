(ns vtranslate.engine.adapters.source.file
  "Driven adapter: read a local filesystem path into text (UTF-8). This is the
   boundary where a file read actually happens. Before slurping, the path is
   validated through hive-system's IPathQuery (exists? + file?) so a missing
   path or a directory is rejected up front onto the closed domain ADT
   (:error/source-unreadable, carrying the offending :source-uri) — and the slurp
   itself is still guarded so any residual IO failure (permission, decode) is
   remapped too, never an escaping exception. hive-system has no content-read
   protocol, so the slurp stays here; it just gains IPathQuery validation.
   Self-registers under wiring/build-port :source (OCP — a new source transport
   is a new adapter + method, not an edit to the core)."
  (:require [clojure.java.io :as io]
            [hive-dsl.result :as r]
            [hive-system.fs.core :as hfs]
            [vtranslate.engine.port.source :as p.src]
            [vtranslate.engine.wiring :as wiring]))

(defrecord FileSource []
  p.src/ISourceReader
  (read-text [_ source-uri]
    (r/let-ok [exists? (hfs/exists? source-uri)
               file?   (hfs/file? source-uri)]
      (cond
        (not exists?)
        (r/err :error/source-unreadable
               {:source-uri source-uri :reason "path does not exist"})

        (not file?)
        (r/err :error/source-unreadable
               {:source-uri source-uri :reason "path is not a regular file"})

        :else
        (try
          (r/ok (slurp (io/file source-uri) :encoding "UTF-8"))
          (catch Exception e
            (r/err :error/source-unreadable
                   {:source-uri source-uri :reason (.getMessage e)})))))))

(defn make-source-reader
  "Build the filesystem source reader."
  []
  (->FileSource))

(defmethod wiring/build-port :source
  [_ _config]
  (r/ok (make-source-reader)))
