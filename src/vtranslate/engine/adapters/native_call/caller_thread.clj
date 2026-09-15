(ns vtranslate.engine.adapters.native-call.caller-thread
  "INativeCall that just calls the thunk, on whatever stack the caller is on.
   Self-registers (defmethod resolve-native-call :caller-thread).

   This is the null object of the port, and it earns its place twice. It is the
   honest identity for a native call that has no stack appetite, and it is what
   a test selects to prove that the seam is what prevents the crash: the same
   workload under :caller-thread and under :sized-stack, one dying and one not,
   is the only evidence that the strategy is doing anything at all.

   It is NOT a safe default for ONNX. See calc.native-stack/measured."
  (:require [hive-dsl.result :as r]
            [vtranslate.engine.port.native-call :as p]
            [vtranslate.engine.providers.native-call-registry :as reg]))

(defrecord CallerThreadCall []
  p/INativeCall
  (call-native [_ f] (f))
  (describe [_] {:strategy :caller-thread}))

(defn make-strategy
  "Build a CallerThreadCall. Takes opts for signature parity with the other
   strategies and reads none of them."
  [_opts]
  (->CallerThreadCall))

(defmethod reg/resolve-native-call :caller-thread [_ config]
  (r/ok (make-strategy (get config :native-call))))
