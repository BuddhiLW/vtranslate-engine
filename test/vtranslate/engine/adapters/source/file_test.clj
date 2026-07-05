(ns vtranslate.engine.adapters.source.file-test
  "FileSource driven adapter — reads a real temp file (UTF-8), and fails loud
   onto :error/source-unreadable for a missing path or a directory. The path is
   validated through hive-system IPathQuery (exists? + file?) before the slurp."
  (:require [clojure.test :refer [deftest is]]
            [hive-dsl.result :as r]
            [vtranslate.engine.adapters.source.file :as file]
            [vtranslate.engine.port.source :as p.src])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def ^:private reader (file/make-source-reader))

(deftest reads-real-temp-srt
  (let [content "1\n00:00:01,000 --> 00:00:04,000\nHello world\n"
        f       (doto (File/createTempFile "vt-src" ".srt") (.deleteOnExit))]
    (spit f content)
    (let [res (p.src/read-text reader (.getPath f))]
      (is (r/ok? res))
      (is (= content (:ok res))))))

(deftest missing-path-is-source-unreadable
  (let [res (p.src/read-text reader "/no/such/vt-missing.srt")]
    (is (r/err? res))
    (is (= :error/source-unreadable (:error res)))))

(deftest directory-path-is-source-unreadable
  (let [dir (str (Files/createTempDirectory "vt-dir" (make-array FileAttribute 0)))
        res (p.src/read-text reader dir)]
    (is (r/err? res))
    (is (= :error/source-unreadable (:error res)))))
