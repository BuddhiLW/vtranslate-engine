(ns vtranslate.engine.collect.watermark-contract-test
  (:require [clojure.test :refer [deftest is]]
            [vtranslate.engine.calc.watermark :as calc]
            [vtranslate.engine.collect.watermark :as mark])
  (:import [java.nio.file Files]
           [javax.imageio ImageIO]
           [java.io File]))

(deftest png-is-sized-written-and-reused
  (let [dir (.toFile (Files/createTempDirectory "mark-contract-" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (let [height 720
            expected (calc/geometry height)
            first-result (mark/png-for (.getPath dir) height)
            file (File. (:path first-result))
            image (ImageIO/read file)]
        (is (.isFile file))
        (is (= (:size expected) (:size first-result) (.getWidth image) (.getHeight image)))
        (is (= (:margin expected) (:margin first-result)))
        (is (= (.getCanonicalPath dir) (.getCanonicalPath (.getParentFile file))))
        (is (= first-result (mark/png-for (.getPath dir) height))))
      (finally
        (doseq [file (.listFiles dir)] (.delete file))
        (.delete dir)))))
