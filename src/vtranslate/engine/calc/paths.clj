(ns vtranslate.engine.calc.paths
  "Pure output-path helpers for the Collect/render boundary — String ops over
   java.io.File parsing only, no filesystem IO."
  (:import [java.io File]))

(defn sibling-output
  "Path beside `source-uri` with its extension replaced by `suffix` (e.g.
   \".subbed.mp4\"): …/<dir>/<base><suffix>. Pure."
  ^String [^String source-uri ^String suffix]
  (let [f      (File. source-uri)
        parent (.getParent f)
        name   (.getName f)
        dot    (.lastIndexOf name ".")
        base   (if (pos? dot) (subs name 0 dot) name)]
    (str (when parent (str parent File/separator)) base suffix)))

(defn both-outputs
  "The :both (softsub+hardsub) variants of `output-uri`:
   {:soft …/<base>.soft.mp4 :hard …/<base>.hard.mp4}. Pure."
  [^String output-uri]
  {:soft (sibling-output output-uri ".soft.mp4")
   :hard (sibling-output output-uri ".hard.mp4")})
