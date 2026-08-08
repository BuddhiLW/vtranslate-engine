(ns vtranslate.engine.adapters.transcript-cache.fressian-file
  "ITranscriptCache over fressian files in a cache directory.

   Writes go through hive-system's IFilesystem atomic-write!, so a reader can
   never observe a half-written transcript — the failure this cache exists to
   survive is a crash partway through a job."
  (:require [clojure.data.fressian :as fress]
            [hive-dsl.result :as r]
            [hive-system.fs.core :as fsq]
            [hive-system.fs.filesystem :as hfs]
            [vtranslate.engine.calc.cache-key :as ck]
            [vtranslate.engine.port.transcript-cache :as port])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]))

(defn default-dir
  "XDG-correct cache location: $XDG_CACHE_HOME/vtranslate/transcripts, else
   ~/.cache/vtranslate/transcripts."
  []
  (let [base (or (not-empty (System/getenv "XDG_CACHE_HOME"))
                 (str (System/getProperty "user.home") "/.cache"))]
    (str base "/vtranslate/transcripts")))

(defn- entry-path [dir key]
  (str dir "/" (ck/cache-file-name key)))

(defn- present? [path]
  (let [res (fsq/file? path)]
    (and (r/ok? res) (true? (:ok res)))))

(defn- ->bytes
  "Fressian-encode `value` to a byte array."
  ^bytes [value]
  (let [out (ByteArrayOutputStream.)]
    (with-open [w (fress/create-writer out)]
      (fress/write-object w value))
    (.toByteArray out)))

(defn- <-bytes
  "Decode a fressian byte array back to Clojure data. create-reader wants an
   InputStream, not a ByteBuffer."
  [^bytes bs]
  (with-open [in (ByteArrayInputStream. bs)]
    (fress/read-object (fress/create-reader in))))

(defrecord FressianFileCache [dir]
  port/ITranscriptCache
  (fetch [_ key]
    (let [path (entry-path dir key)]
      (if-not (present? path)
        (r/ok nil)
        (r/try-effect* :error/transcript-cache-read
          (<-bytes (java.nio.file.Files/readAllBytes
                    (java.nio.file.Path/of ^String path (into-array String []))))))))

  (store! [_ key transcript]
    (r/let-ok [_ (hfs/mkdirs! dir)
               _ (hfs/atomic-write! (entry-path dir key) (->bytes transcript))]
      (r/ok key)))

  (forget! [_ key]
    (r/try-effect* :error/transcript-cache-forget
      (do (java.nio.file.Files/deleteIfExists
           (java.nio.file.Path/of ^String (entry-path dir key)
                                  (into-array String [])))
          key)))

  (evict! [_ older-than-seconds]
    (let [age (long (or older-than-seconds 0))]
      (if-not (pos? age)
        (r/ok 0)
        (r/try-effect* :error/transcript-cache-evict
          (let [cutoff (- (System/currentTimeMillis) (* 1000 age))]
            (->> (.listFiles (java.io.File. ^String dir))
                 seq
                 (filter #(and (.isFile ^java.io.File %)
                               (.endsWith (.getName ^java.io.File %) ".fressian")
                               (< (.lastModified ^java.io.File %) cutoff)))
                 (filter #(.delete ^java.io.File %))
                 count)))))))

(defn make-cache
  "The production cache. `dir` overrides the XDG location."
  ([] (make-cache nil))
  ([dir] (->FressianFileCache (or (not-empty (str (or dir ""))) (default-dir)))))
