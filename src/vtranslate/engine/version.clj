(ns vtranslate.engine.version
  "The running engine's own version, read from the artifact that carries it.

   This exists for CACHE IDENTITY. A transcript may only be reused when every
   input that could change it is identical, and the ENGINE ITSELF is one of
   those inputs: improve the segmenter, the hygiene rules or the transcriber
   adapter and the same audio under the same settings now yields a better
   transcript. Without the version in the key, that improvement is invisible for
   every video already processed, and the only way to see it is to bump a
   hand-maintained constant somebody has to remember.

   Read from META-INF/maven/<group>/<artifact>/pom.properties, which
   clojure.tools.build writes into the jar. Absent that file we are running from
   source, so the answer is \"dev\": one shared bucket for every working-tree
   run, which is the right grain, because a source tree changes constantly and
   a per-run key would make the cache useless in development."
  (:require [clojure.java.io :as io])
  (:import [java.util Properties]))

(def ^:private pom-path
  "META-INF/maven/io.github.buddhilw/vtranslate-engine/pom.properties")

(def engine-version
  "The running engine's version string, or \"dev\" from a source checkout.
   Resolved ONCE at load: it cannot change while the process lives, and a
   per-call read would touch the classpath on every transcription."
  (or (try
        (when-let [url (io/resource pom-path)]
          (with-open [in (io/input-stream url)]
            (let [p (Properties.)]
              (.load p in)
              (some-> (.getProperty p "version") not-empty))))
        (catch Exception _ nil))
      "dev"))
