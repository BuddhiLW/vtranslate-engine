(ns vtranslate.engine.collect.process
  "Collect: running an executable. One protocol over ProcessBuilder so every
   boundary that shells out (the ffmpeg CLI burner today) takes the runner
   as a value, and a test hands it a reified fake instead of a shell script."
  (:require [clojure.java.io :as io])
  (:import [java.io IOException]
           [java.lang ProcessBuilder$Redirect]))

(defprotocol IProcessRunner
  "Run `argv` (a vector of strings, the executable first) to completion.
   `exec!` discards stdout and keeps stderr, for a command whose output is a
   file; `capture!` discards stderr and keeps stdout, for a command whose
   output is text. Both throw java.io.IOException when the executable cannot
   be started."
  (exec! [runner argv] "=> {:exit n :stderr s}")
  (capture! [runner argv] "=> {:exit n :out s}"))

(defn- start ^Process [argv configure]
  (let [pb (ProcessBuilder. ^java.util.List (vec (map str argv)))]
    (configure pb)
    (.start pb)))

(defn- drain [stream]
  (with-open [r (io/reader stream)] (slurp r)))

(def system-runner
  "The runner over the host's processes."
  (reify IProcessRunner
    (exec! [_ argv]
      (let [p (start argv #(.redirectOutput ^ProcessBuilder % ProcessBuilder$Redirect/DISCARD))
            err (drain (.getErrorStream p))]
        {:exit (.waitFor p) :stderr err}))
    (capture! [_ argv]
      (let [p (start argv #(.redirectError ^ProcessBuilder % ProcessBuilder$Redirect/DISCARD))
            out (drain (.getInputStream p))]
        {:exit (.waitFor p) :out out}))))

(defn starts?
  "Whether `runner` can start `argv` and it exits zero. False, never a throw,
   for a missing executable or a permission problem, so a probe at wiring
   time degrades a choice rather than failing the process."
  [runner argv]
  (try
    (zero? (:exit (exec! runner argv)))
    (catch IOException _ false)
    (catch SecurityException _ false)))

(defn output-of
  "stdout of `argv` under `runner` when it exits zero, else nil. Same
   tolerance of a missing executable as `starts?`."
  [runner argv]
  (try
    (let [{:keys [exit out]} (capture! runner argv)]
      (when (zero? exit) out))
    (catch IOException _ nil)
    (catch SecurityException _ nil)))
