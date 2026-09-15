(ns vtranslate.engine.adapters.segmenter.silero-vad-stack-int-test
  "The assertion that pins the onnxruntime upgrade: a real ONNX session, created
   as the FIRST one in a fresh process, under each INativeCall strategy.

   Why a subprocess. The failure is a native stack overflow, which arrives as
   SIGSEGV: no StackOverflowError, no Result, no hs_err, and nothing left of the
   JVM to report it. A test that made this call in-process would not fail, it
   would delete the test run. Forking turns the process death into an exit
   status, which is an ordinary value to assert on.

   Why the FIRST session matters. ONNX Runtime does its deep graph work once per
   process. A second session on a tiny stack succeeds, so a probe that creates a
   warm-up session first reports that every stack size is fine. Each fork below
   therefore creates exactly one session and then exits.

   Requires the :silero-vad alias and models/silero_vad.onnx; both are skipped
   for cleanly when absent, because this suite also runs where neither is set up.

     clojure -M:test:itest:silero-vad"
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [vtranslate.engine.calc.native-stack :as calc]))

(def ^:private model "models/silero_vad.onnx")

(defn- available?
  "Whether this JVM can actually run the experiment: the model on disk and the
   ONNX classes on the classpath."
  []
  (and (.exists (io/file model))
       (try (Class/forName "ai.onnxruntime.OrtEnvironment") true
            (catch Throwable _ false))))

(def ^:private program
  "One ONNX session on a thread of a given stack size, then print and exit.
   Deliberately NOT using the engine's own namespaces: this has to be the bare
   mechanism, so that a future refactor of the adapter cannot accidentally make
   the experiment pass by routing around it."
  (str "(import '[ai.onnxruntime OrtEnvironment OrtSession$SessionOptions])"
       "(let [bytes (Long/parseLong (first *command-line-args*))"
       "      out (atom nil)"
       "      t (Thread. nil"
       "                 ^Runnable (fn [] (let [env (OrtEnvironment/getEnvironment)"
       "                                        opts (doto (OrtSession$SessionOptions.)"
       "                                               (.setInterOpNumThreads 1)"
       "                                               (.setIntraOpNumThreads 1)"
       "                                               (.addCPU true))]"
       "                                    (with-open [s (.createSession env \"" model "\" opts)]"
       "                                      (reset! out (.getNumInputs s)))))"
       "                 \"probe\" bytes)]"
       "  (.start t) (.join t)"
       "  (println \"SESSION-INPUTS\" @out)"
       "  (shutdown-agents)"
       "  (System/exit 0))"))

(defn- fork-session
  "Create one ONNX session in a FRESH JVM on a `stack-bytes` thread.
   => {:exit n :survived? bool}"
  [stack-bytes]
  (let [{:keys [exit out]} (sh/sh "clojure" "-M:silero-vad" "-e" program
                                  "--" (str stack-bytes))]
    {:exit exit
     :survived? (and (zero? exit) (str/includes? (str out) "SESSION-INPUTS 3"))}))

(deftest ^:integration the-sized-stack-is-what-keeps-onnx-alive
  (if-not (available?)
    (println "SKIP silero-vad-stack-int-test: needs :silero-vad and" model)
    (do
      (testing "the strategy's own stack size creates a session and returns"
        (let [big (fork-session calc/default-stack-bytes)]
          (is (:survived? big)
              (str "onnxruntime could not create its first session even on "
                   calc/default-stack-bytes
                   " bytes; raise calc.native-stack/default-stack-bytes and "
                   "re-measure before shipping"))))

      (testing "and the size measured to fail still fails, so the fix is load-bearing"
        (let [small (fork-session (:dies-at calc/measured))]
          (is (not (:survived? small))
              (str "a first ONNX session now survives " (:dies-at calc/measured)
                   " bytes. That is good news, not a passing test: onnxruntime "
                   "has changed its appetite, so re-measure and update "
                   "calc.native-stack/measured rather than deleting this."))))

      (testing "the minimum the sizing policy will hand out is itself survivable"
        (is (:survived? (fork-session calc/minimum-stack-bytes)))))))
