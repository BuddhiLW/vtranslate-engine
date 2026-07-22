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
