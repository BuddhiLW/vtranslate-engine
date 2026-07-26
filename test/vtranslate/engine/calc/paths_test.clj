(ns vtranslate.engine.calc.paths-test
  "Pure sibling-output path derivation — unit cases + a property (suffix always
   applied, parent dir preserved, last extension replaced)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [vtranslate.engine.calc.paths :as sut]))

(deftest sibling-output-cases
  (is (= "/a/b/movie.subbed.mp4"  (sut/sibling-output "/a/b/movie.mp4" ".subbed.mp4")))
  (is (= "movie.subbed.mp4"       (sut/sibling-output "movie.mkv" ".subbed.mp4")))
  (is (= "/a/b/no-ext.subbed.mp4" (sut/sibling-output "/a/b/no-ext" ".subbed.mp4")))
  (is (= "/a/b/a.b.c.subbed.mp4"  (sut/sibling-output "/a/b/a.b.c.avi" ".subbed.mp4"))
      "only the LAST extension is replaced"))

(deftest both-outputs-cases
  (is (= {:soft "/a/b/movie.pt-BR.soft.mp4" :hard "/a/b/movie.pt-BR.hard.mp4"}
         (sut/both-outputs "/a/b/movie.pt-BR.mp4")))
  (is (= {:soft "movie.soft.mp4" :hard "movie.hard.mp4"}
         (sut/both-outputs "movie.mkv"))
      "the source extension is replaced, not appended"))

(defspec sibling-output-applies-suffix-and-keeps-parent 200
  (prop/for-all [nm  (gen/not-empty gen/string-alphanumeric)
                 ext (gen/elements ["mp4" "mkv" "avi" "webm" ""])]
    (let [src (str "/dir/" nm (when (seq ext) (str "." ext)))
          out (sut/sibling-output src ".subbed.mp4")]
      (and (str/ends-with? out ".subbed.mp4")
           (str/starts-with? out "/dir/")
           (str/includes? out nm)))))
