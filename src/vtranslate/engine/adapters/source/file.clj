(ns vtranslate.engine.adapters.source.file
  "Driven adapter: read a local filesystem path into text (UTF-8). This is the
   boundary where a file read actually happens — slurp is guarded so any IO
   failure (missing file, permission, decode) is remapped onto the closed domain
   ADT (:error/source-unreadable, carrying the offending :source-uri), never an
   escaping exception. Self-registers under wiring/build-port :source (OCP — a new
   source transport is a new adapter + method, not an edit to the core)."
  (:require [clojure.java.io :as io]
            [hive-dsl.result :as r]
            [vtranslate.engine.port.source :as p.src]
            [vtranslate.engine.wiring :as wiring]))

(defrecord FileSource []
  p.src/ISourceReader
  (read-text [_ source-uri]
    (try
      (r/ok (slurp (io/file source-uri) :encoding "UTF-8"))
      (catch Exception e
        (r/err :error/source-unreadable
               {:source-uri source-uri :reason (.getMessage e)})))))

(defn make-source-reader
  "Build the filesystem source reader."
  []
  (->FileSource))

(defmethod wiring/build-port :source
  [_ _config]
  (r/ok (make-source-reader)))
