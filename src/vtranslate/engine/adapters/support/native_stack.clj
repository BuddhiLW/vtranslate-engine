(ns vtranslate.engine.adapters.support.native-stack
  "Run a native call on a thread with a stack big enough for it.

   ONNX Runtime >= 1.29 recurses deeply while it builds and optimizes a session
   graph, and it does so on the CALLER's stack. On the JVM's default thread
   stack that overflow lands in native code, where there is no StackOverflowError
   to catch: the process takes SIGSEGV and dies outright — no Result, no error
   message, no hs_err file. Measured 2026-09-14 on linux-x86_64 / JDK 26 with
   silero_vad.onnx: onnxruntime 1.29.0 and 1.30.0 both die at OrtSession
   creation on the default stack and both succeed under -Xss4m, while 1.20.0 and
   1.22.0 fit in the default stack, which is why the crash looked like a version
   regression and was nearly 'fixed' by pinning an old runtime.

   A launch flag would fix it only for whoever remembers to pass it — the engine
   runs as a CLI subprocess, inside the app server, and under a REPL. Owning the
   thread is what makes the fix travel with the code."
  (:require [hive-dsl.result :as r]))

(def default-stack-bytes
  "16 MiB. -Xss4m was enough for silero_vad.onnx in the measurement above; this
   leaves room for a bigger graph without being a meaningful allocation, since
   the thread is created per call and lives only for it."
  (* 16 1024 1024))

(defn call-with-stack
  "Invoke `f` on a fresh thread with a `stack-bytes` stack and return its value.
   A throwable from `f` is rethrown on the calling thread, so this is invisible
   to any error handling around it — including `r/guard` / `r/try-effect*`.
   Blocking: the caller waits for the thread to finish."
  ([f] (call-with-stack default-stack-bytes f))
  ([stack-bytes f]
   (let [result (atom nil)
         thrown (atom nil)
         t      (Thread. nil
                         ^Runnable (fn []
                                     (try
                                       (reset! result (f))
                                       (catch Throwable e (reset! thrown e))))
                         "vtranslate-native-stack"
                         (long stack-bytes))]
     (.start t)
     (.join t)
     (if-let [e @thrown]
       (throw e)
       @result))))

(defmacro with-stack
  "Evaluate `body` on a thread with the default big stack. => the body's value."
  [& body]
  `(call-with-stack (fn [] ~@body)))

(defn result-with-stack
  "`call-with-stack` for a thunk that already returns a Result: a throwable
   becomes `(r/err error-key {:reason ...})` rather than escaping.
   => Result."
  [error-key f]
  (r/guard Throwable (r/err error-key {:reason "native call failed off the main stack"})
    (call-with-stack f)))
