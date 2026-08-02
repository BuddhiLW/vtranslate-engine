(ns vtranslate.engine.adapters.composer.support
  "Shared effects for the video composers: encode beside the target under a
   unique temp name, then rename atomically on success — a concurrent or
   interrupted run never leaves a truncated mp4 at the real path."
  (:import [java.nio.file Files Path StandardCopyOption]))

(defn atomically
  "Call (write! tmp-path) to produce the artifact under a sibling temp name,
   then rename it over `out` atomically. The temp is deleted on any failure.
   => out."
  [out write!]
  (let [tmp (str out ".part-" (System/nanoTime))]
    (try
      (write! tmp)
      (Files/move (Path/of tmp (into-array String []))
                  (Path/of out (into-array String []))
                  (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE
                                                  StandardCopyOption/REPLACE_EXISTING]))
      out
      (catch Throwable t
        (Files/deleteIfExists (Path/of tmp (into-array String [])))
        (throw t)))))
