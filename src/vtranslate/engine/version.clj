(ns vtranslate.engine.version
  "The running engine's own version."
  (:require [clojure.java.io :as io])
  (:import [java.util Properties]))

(def ^:private pom-path
  "META-INF/maven/io.github.buddhilw/vtranslate-engine/pom.properties")

(def engine-version
  "Version string of the running engine, or \"dev\" from a source checkout.
   Resolved once at load. => string, never nil or blank"
  (or (try
        (when-let [url (io/resource pom-path)]
          (with-open [in (io/input-stream url)]
            (let [p (Properties.)]
              (.load p in)
              (some-> (.getProperty p "version") not-empty))))
        (catch Exception _ nil))
      "dev"))
