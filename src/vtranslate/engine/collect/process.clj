(ns vtranslate.engine.collect.process
  "Collect: running an executable. One protocol over ProcessBuilder so every
   boundary that shells out (the ffmpeg CLI burner today) takes the runner
   as a value, and a test hands it a reified fake instead of a shell script.

   Every media subprocess in the system funnels through here: the ffmpeg probe
   and burn in collect.ffmpeg-cli, and the tesseract frame grabs and audio
   slices in vtranslate-context, which inject one of these runners rather than
   starting their own processes. That makes this the single place where the
   child's ENVIRONMENT is decided, and `system-runner` decides it by allowlist
   (calc.subprocess-env) rather than by inheritance."
  (:require [clojure.java.io :as io]
            [vtranslate.engine.calc.subprocess-env :as subprocess-env])
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

(defn- start ^Process [argv env configure]
  (let [pb (ProcessBuilder. ^java.util.List (vec (map str argv)))]
    (when env
      (doto (.environment pb)
        (.clear)
        (.putAll ^java.util.Map env)))
    (configure pb)
    (.start pb)))

(defn- drain [stream]
  (with-open [r (io/reader stream)] (slurp r)))

(defn runner
  "An IProcessRunner over the host's processes whose children see exactly
   `env`, a name to value map. A nil `env` inherits the JVM's own, which is
   what no media tool should get: prefer `system-runner`, or pass
   `(subprocess-env/sanitize (System/getenv) policy)` with a widened policy."
  [env]
  (reify IProcessRunner
    (exec! [_ argv]
      (let [p (start argv env #(.redirectOutput ^ProcessBuilder % ProcessBuilder$Redirect/DISCARD))
            err (drain (.getErrorStream p))]
        {:exit (.waitFor p) :stderr err}))
    (capture! [_ argv]
      (let [p (start argv env #(.redirectError ^ProcessBuilder % ProcessBuilder$Redirect/DISCARD))
            out (drain (.getInputStream p))]
        {:exit (.waitFor p) :out out}))))

(def system-runner
  "The runner over the host's processes.

   Children get the SANITIZED environment, never the worker's. ffmpeg and
   tesseract parse attacker-influenced bytes and have no use for a provider
   credential, so the allowlist in calc.subprocess-env is what they see. The
   environment is read once here rather than per run: it cannot change under a
   running JVM, and capturing it makes the policy a property of this value."
  (runner (subprocess-env/sanitize (System/getenv))))

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
