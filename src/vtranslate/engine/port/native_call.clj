(ns vtranslate.engine.port.native-call
  "Port: HOW a native call is executed, as opposed to what it does.

   Some native libraries need more of the caller's stack than a JVM thread is
   born with. ONNX Runtime is the one that forced this port into existence: from
   1.29 it recurses deeply while building and optimising a session graph, and it
   does that recursion on whatever stack the caller happens to be standing on.
   Overflow there lands in native code, where there is no StackOverflowError to
   catch: the process takes SIGSEGV and dies. No Result, no message, no hs_err.

   That is a property of the CALL, not of the segmenter, the transcriber or any
   other caller, so it is a port rather than a helper buried in one adapter.
   Making it a protocol buys the two things a bare function could not:

   - SUBSTITUTION. A caller depends on `call-native` and never on which strategy
     is behind it, so \"run this on a big stack\" and \"run this right here\" are
     the same call site. A test can pass a recording strategy and assert the
     seam without going anywhere near a native library.

   - EXTENSION. A deployment that needs a different discipline (a pinned carrier
     thread, an affinity-bound executor, a strategy that also sets a native
     thread name for a profiler) registers one in its own repo and names it in
     config. Nothing here is edited to admit it."
  (:require [hive-dsl.result :as r]))

(defprotocol INativeCall
  "A discipline for invoking a thunk that is about to enter native code."
  (call-native [this f]
    "Invoke `f` under this strategy and return its value. A throwable from `f`
     is rethrown to the CALLER, so ordinary error handling around this call
     keeps working and a strategy never becomes a place exceptions go to die.")
  (describe [this]
    "What this strategy is, as data, for logs and job telemetry.
     => {:strategy keyword, ...}"))

(defn call-result
  "`call-native` for a thunk that already returns a Result: a throwable becomes
   `(r/err error-key ...)` instead of escaping.

   Note what this CANNOT do. A native stack overflow is not a throwable and
   never reaches this function; the process is simply gone. This converts the
   failures that remain expressible, which is why choosing the right strategy is
   the safety property and this is only tidiness around it.
   => Result."
  [strategy error-key f]
  (r/guard Throwable (r/err error-key {:reason "native call failed"})
    (call-native strategy f)))
