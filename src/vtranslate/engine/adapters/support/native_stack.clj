(ns vtranslate.engine.adapters.support.native-stack
  "Convenience facade over the INativeCall port, for the common case: run this
   thunk on a stack big enough for a deep native call.

   The mechanism used to live here as a bare function. It moved to a port with
   two registered strategies (adapters.native-call.sized-stack and
   .caller-thread) for a reason the bug itself supplied: the only way to show
   that the big stack is what prevents the crash is to run the SAME workload
   with and without it, and a hard-coded thread offers no way to say 'without'.
   The strategies are also what let a deployment choose a different discipline
   without editing this namespace.

   This facade stays because most callers genuinely want the default and should
   not have to resolve a strategy to say so.

   See calc.native-stack/measured for what was observed and at which sizes."
  (:require [vtranslate.engine.adapters.native-call.sized-stack :as sized]
            [vtranslate.engine.calc.native-stack :as calc]
            [vtranslate.engine.port.native-call :as p]))

(def default-stack-bytes
  "Re-exported from calc.native-stack, where the number is justified."
  calc/default-stack-bytes)

(def default-strategy
  "The one almost every caller wants."
  (sized/make-strategy nil))

(defn call-with-stack
  "Invoke `f` on a thread with a `stack-bytes` stack and return its value.
   A throwable from `f` is rethrown on the calling thread, so this is invisible
   to any error handling around it, `r/guard` and `r/try-effect*` included.
   Blocking: the caller waits for the thread to finish.

   An explicitly passed `stack-bytes` is used VERBATIM, WITHOUT the
   configuration floor, and that asymmetry is deliberate. A size arriving from
   config is someone guessing, and guessing low is unsurvivable, so
   calc.native-stack raises it. A size written at a call site is someone
   choosing, and the only way to show that the big stack is what prevents the
   crash is to ask for a small one on purpose and watch the same work fail."
  ([f] (p/call-native default-strategy f))
  ([stack-bytes f] (p/call-native (sized/->SizedStackCall (long stack-bytes)) f)))

(defmacro with-stack
  "Evaluate `body` on a thread with the default big stack. => the body's value."
  [& body]
  `(call-with-stack (fn [] ~@body)))

(defn result-with-stack
  "`call-with-stack` for a thunk that already returns a Result: a throwable
   becomes `(r/err error-key {:reason ...})` rather than escaping.
   => Result."
  [error-key f]
  (p/call-result default-strategy error-key f))
