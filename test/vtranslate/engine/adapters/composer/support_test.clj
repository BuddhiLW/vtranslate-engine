(ns vtranslate.engine.adapters.composer.support-test
  (:require [clojure.test :refer [deftest is]]
            [vtranslate.engine.adapters.composer.support :as sut])
  (:import [java.nio.file Files]))

(defn- temp-target []
  (str (Files/createTempFile "vt-compose" ".mp4" (into-array java.nio.file.attribute.FileAttribute []))))

(defn- sibling-temps [out]
  (let [parent (java.io.File. (.getParent (java.io.File. out)))]
    (filter #(.contains ^String % ".part-") (.list parent))))

(deftest atomically-renames-the-finished-artifact-over-the-target
  (let [out (temp-target)]
    (is (= out (sut/atomically out #(spit % "finished"))))
    (is (= "finished" (slurp out)))
    (is (empty? (sibling-temps out))
        "no temp corpse left beside the target")))

(deftest atomically-deletes-the-temp-and-rethrows-on-failure
  (let [out (temp-target)]
    (is (thrown? Exception
                 (sut/atomically out (fn [tmp]
                                       (spit tmp "truncated")
                                       (throw (ex-info "encode died" {}))))))
    (is (= "" (slurp out)) "target untouched by the failed write")
    (is (empty? (sibling-temps out))
        "the temp file is gone")))

(defn- partials-of
  "Names of the `.part-*` files beside `out` that belong to `out`."
  [out]
  (let [file (java.io.File. ^String out)
        base (str (.getName file) ".part-")]
    (into #{} (filter #(.startsWith ^String % base))
          (.list (.getParentFile file)))))

(defn- age! [path millis]
  (Files/setLastModifiedTime
   (java.nio.file.Path/of ^String path (into-array String []))
   (java.nio.file.attribute.FileTime/fromMillis
    (- (System/currentTimeMillis) millis))))

(deftest a-partial-abandoned-by-a-killed-run-is-reaped-by-the-next-one
  (let [out  (temp-target)
        dead (str out ".part-11111")
        live (str out ".part-22222")]
    (spit dead "wreckage from a run that was SIGKILLed")
    (age! dead (* 24 60 60 1000))
    (spit live "a concurrent run, still writing")

    (is (= out (sut/atomically out #(spit % "finished"))))
    (is (= "finished" (slurp out)) "the new artifact still lands")

    (is (not (contains? (partials-of out) (.getName (java.io.File. dead))))
        "a partial older than any compose is wreckage, and is taken")
    (is (contains? (partials-of out) (.getName (java.io.File. live)))
        "a fresh partial belongs to a run that may still be writing")

    (Files/deleteIfExists (java.nio.file.Path/of ^String live
                                                 (into-array String [])))))
