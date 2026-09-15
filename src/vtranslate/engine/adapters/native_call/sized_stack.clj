(ns vtranslate.engine.adapters.native-call.sized-stack
  "INativeCall that runs the thunk on a thread created with an explicit stack
   size. Self-registers (defmethod resolve-native-call :sized-stack).

   Owning the thread is what makes the fix travel with the code. A -Xss launch
   flag would work too, and would hold only for whoever remembered to pass it:
   the engine runs as a CLI subprocess, inside the app server, under a REPL, and
   from a test runner, and each of those is a different launcher. A thread this
   namespace creates has the stack this namespace asked for in all four."
  (:require [hive-dsl.result :as r]
            [vtranslate.engine.calc.native-stack :as calc]
            [vtranslate.engine.port.native-call :as p]
            [vtranslate.engine.providers.native-call-registry :as reg]))

(def thread-name
  "Named so a thread dump taken during a burn or a hung job says which seam the
   frame belongs to."
  "vtranslate-native-stack")

(defrecord SizedStackCall [stack-bytes]
  p/INativeCall
  (call-native [_ f]
    (let [result (atom nil)
          thrown (atom nil)
          t (Thread. nil
                     ^Runnable (fn []
                                 (try
                                   (reset! result (f))
                                   (catch Throwable e (reset! thrown e))))
                     thread-name
                     (long stack-bytes))]
      (.start t)
      ;; Blocking on purpose. The caller is mid-pipeline and its next stage
      ;; needs this value; handing back a future would only move the join.
      (.join t)
      (if-let [e @thrown]
        (throw e)
        @result)))

  (describe [_] {:strategy :sized-stack :stack-bytes stack-bytes}))

(defn make-strategy
  "Build a SizedStackCall from `opts` ({:stack-bytes n}), defaulted and floored
   by calc.native-stack."
  [opts]
  (->SizedStackCall (calc/stack-bytes opts)))

(defmethod reg/resolve-native-call :sized-stack [_ config]
  (r/ok (make-strategy (get config :native-call))))
