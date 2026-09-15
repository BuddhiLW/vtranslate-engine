(ns vtranslate.engine.calc.native-stack
  "Pure: how much stack a native workload is given, and why that number.

   Separated from the adapter that spawns the thread so the SIZE is a value a
   test can assert and a config can override, rather than a literal buried in
   the code that allocates.")

(def measured
  "What was actually observed, not what seemed safe. Recorded here because the
   numbers below are only defensible next to their evidence, and because the
   next person to bump onnxruntime needs to know what to re-run.

   linux-x86_64, JDK 26, silero_vad.onnx, onnxruntime 1.30.0, bisected by
   creating the session as the FIRST ONNX session in a fresh process:

     256 KiB  -> dies      2 MiB -> dies
       1 MiB  -> dies      3 MiB -> survives, and every size above it

   A death here is the JVM taking SIGSEGV: no exception, no exit status worth
   reading, no hs_err file.

   TWO TRAPS, both of which nearly buried this:

   1. FIRST-SESSION ONLY. ONNX Runtime does its deep graph work once per
      process, so a SECOND session on a 256 KiB stack succeeds. A probe that
      creates a warm-up session before measuring reports that every stack size
      is fine.
   2. IT IS NOT A VERSION REGRESSION. onnxruntime 1.20 and 1.22 fit inside the
      default stack and 1.29+ does not, so the crash presents as 'the new
      version is broken' and is nearly 'fixed' by pinning an old one. The old
      pin was luck, not safety: any runtime whose graph work grows past the
      default stack fails the same way."
  {:runtime "onnxruntime 1.30.0"
   :model "silero_vad.onnx"
   :platform "linux-x86_64 / JDK 26"
   :dies-at (* 2 1024 1024)
   :survives-at (* 3 1024 1024)})

(def minimum-stack-bytes
  "8 MiB, against a measured floor of 3. The margin is deliberate: the cost of
   overshooting is a few pages of address space on a thread that lives for one
   call, and the cost of undershooting is a process death with no diagnostic.
   A different model or a later runtime will have its own appetite, and this
   should absorb that without anyone noticing."
  (* 8 1024 1024))

(def default-stack-bytes
  "16 MiB. Double the floor again, for the same asymmetry."
  (* 16 1024 1024))

(defn stack-bytes
  "The stack size `opts` asks for, or the default. A value below
   `minimum-stack-bytes` is RAISED to it rather than honoured: the whole point
   of this seam is that a too-small stack is unsurvivable, so quietly
   respecting one would defeat it."
  ^long [opts]
  (let [asked (:stack-bytes opts)]
    (if (and (number? asked) (pos? asked))
      (max minimum-stack-bytes (long asked))
      default-stack-bytes)))
