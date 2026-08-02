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
