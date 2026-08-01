(ns vtranslate.engine.calc.cache-key
  "Pure — identity of a transcription. Two runs may reuse a transcript only when
   every input that could change it is identical: the audio, the backend, the
   weights, the language hint and the segmentation."
  (:require [clojure.string :as str])
  (:import [java.security MessageDigest]))

(defn- hex [^bytes bs]
  (str/join (map #(format "%02x" %) bs)))

(defn digest
  "Lowercase sha-256 hex of `s`."
  [s]
  (hex (.digest (MessageDigest/getInstance "SHA-256")
                (.getBytes (str s) "UTF-8"))))

(def ^:private key-fields
  "Every input that can change a transcript. Order is fixed so the key is stable
   across runs; a new field must be APPENDED, never inserted, or every existing
   cache entry silently misses. The audio is identified by CONTENT, not path, so
   renaming or moving a video still hits and a same-sized replacement misses."
  [:content-sha :provider :model :language :segmenter :span-pad-ms])

(defn transcript-key
  "Stable cache key for one transcription. `inputs` supplies :content-sha and the
   ASR settings; anything absent contributes an empty slot rather than being
   skipped, so {:model nil} and {} cannot collide."
  [inputs]
  (digest (str/join " " (map #(str (get inputs %)) key-fields))))

(def ^:private chunk-bytes (* 1024 1024))

(defn file-sha
  "Streaming sha-256 of a file's CONTENT, or nil when it cannot be read.
   Streamed in 1 MiB chunks so a multi-gigabyte video never lands in memory."
  [path]
  (try
    (let [md (MessageDigest/getInstance "SHA-256")
          buf (byte-array chunk-bytes)]
      (with-open [in (java.io.FileInputStream. (str path))]
        (loop []
          (let [n (.read in buf)]
            (when (pos? n)
              (.update md buf 0 n)
              (recur)))))
      (hex (.digest md)))
    (catch Exception _ nil)))

(defn cache-file-name
  "File name for `key` under the cache directory."
  [key]
  (str key ".fressian"))
