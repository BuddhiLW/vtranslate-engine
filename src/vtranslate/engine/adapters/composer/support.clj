(ns vtranslate.engine.adapters.composer.support
  "Shared effects for the video composers: encode beside the target under a
   unique temp name, then rename atomically on success — a concurrent or
   interrupted run never leaves a truncated mp4 at the real path."
  (:import [java.nio.file Files Path StandardCopyOption]
[java.nio.file LinkOption]))

(def ^:private stale-partial-age-ms
  "How old a leftover `.part-*` beside the same target must be before a later
   run reaps it. Longer than any single compose, so a sibling that is still
   writing is never mistaken for wreckage."
  (* 6 60 60 1000))

(defn- stale-partial?
  "Whether `path` was last written longer ago than `stale-partial-age-ms`."
  [^Path path now]
  (> (- now (.toMillis (Files/getLastModifiedTime
                        path (into-array LinkOption []))))
     stale-partial-age-ms))

(defn- reap-partials!
  "Delete `.part-*` files beside `out` left behind by a run that never unwound.

   `atomically`'s catch does not run on a SIGKILL, an OOM kill or an eviction,
   so its temp outlives the process and nothing else ever removes it. Only
   partials older than `stale-partial-age-ms` are taken, which leaves a
   concurrent run's own temp alone. Best effort: a partial that cannot be read
   or deleted is left where it is."
  [^Path out]
  (when-let [parent (.getParent out)]
    (let [now (System/currentTimeMillis)]
      (try
        (with-open [entries (Files/newDirectoryStream
                             parent (str (.getFileName out) ".part-*"))]
          (doseq [^Path entry entries]
            (try
              (when (stale-partial? entry now)
                (Files/deleteIfExists entry))
              (catch Throwable _ nil))))
        (catch Throwable _ nil)))))

(defn atomically
  "Call (write! tmp-path) to produce the artifact under a sibling temp name,
   then rename it over `out` atomically. The temp is deleted on any failure,
   and temps abandoned by an earlier run at the same target are reaped first.
   => out."
  [out write!]
  (let [out-path (Path/of out (into-array String []))
        tmp      (str out ".part-" (System/nanoTime))]
    (reap-partials! out-path)
    (try
      (write! tmp)
      (Files/move (Path/of tmp (into-array String []))
                  out-path
                  (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE
                                                  StandardCopyOption/REPLACE_EXISTING]))
      out
      (catch Throwable t
        (Files/deleteIfExists (Path/of tmp (into-array String [])))
        (throw t)))))
