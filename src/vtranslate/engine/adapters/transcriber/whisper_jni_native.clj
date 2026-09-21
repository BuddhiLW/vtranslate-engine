(ns vtranslate.engine.adapters.transcriber.whisper-jni-native
  "NATIVE half of the whisper.cpp/JNI adapter — the ONLY ns that (:import ...)s the
   io.github.givimad.whisperjni types, so it is loadable ONLY when the :whisper-jni
   alias is on the classpath. The core-safe outer ns
   (adapters.transcriber.whisper-jni) lazy-(require)s this via requiring-resolve
   AFTER its Class/forName probe passes — never on the bare core cp (mirrors how
   collect.ffmpeg's bytedeco import is quarantined away from collect.port).

   HAZARDS encoded below:
   - WhisperJNI/loadLibrary extracts + dlopen's the native lib; it must run ONCE
     per JVM, so it hides behind a `delay`. Deref throwing (missing/mis-linked
     libwhisper.so) propagates up to the caller's r/try-effect* -> :error/asr-failed.
   - Model weights are large (large-v3 ~3 GB); re-init per call is untenable, so a
     loaded WhisperContext is CACHED per (model-path, use-gpu?). The cache used to
     hold every context for the JVM's life and free none of them, which was safe
     while one model decoded everything. Routing loads a second, and whisper.cpp
     does not fail when weights do not fit: it walks into a null ggml buffer and
     ABORTS THE PROCESS inside libggml-base. So the cache now carries a VRAM
     budget, gives the least recently used context back to the device to stay
     under it, and refuses the load outright when it cannot. The decision is pure
     and lives in whisper-budget; only the numbers are collected here.
   - whisper.cpp's default-state `full` is NOT reentrant on one context: two
     concurrent decodes on the same WhisperContext corrupt each other. We serialize
     `full` per context with `locking`, so parallelism across DIFFERENT models is
     free while same-context calls are ordered.
   - whisper segment timestamps are CENTISECONDS; ->ms is *10 here, and the raw
     {:start-ms :end-ms :text} maps go to support/normalize-segments upstream (no
     per-segment confidence -> the normalizer's 1.0 default applies)."
  (:require [clojure.string :as str]
            [hive-dsl.result :as r]
            [vtranslate.engine.adapters.transcriber.support :as sup]
            [vtranslate.engine.adapters.transcriber.whisper-budget :as budget]
            [clojure.java.shell :as sh])
  (:import [io.github.givimad.whisperjni WhisperJNI WhisperContext
                                         WhisperContextParams WhisperFullParams]
           [java.nio.file Path]))

(def ^:private jni
  "Process-global WhisperJNI handle. loadLibrary is idempotent-once and must not
   race, so the extract+link happens exactly once behind this delay; deref throws
   loudly when the native lib can't be found/linked."
  (delay
    (WhisperJNI/loadLibrary)
    (WhisperJNI.)))

(def ^:private ctx-cache
  "model-key -> {:ctx WhisperContext :bytes n}, plus the bookkeeping the VRAM
   budget needs: which keys have a decode in flight, how recently each was used,
   and what the device lets us hold.

   Contexts used to be kept for the JVM's lifetime and never freed. That was
   safe while one model decoded everything and fatal as soon as routing loaded a
   second: whisper.cpp does not fail when weights do not fit, it aborts the
   process inside libggml. So the cache now has a ceiling, gives back the least
   recently used context to stay under it, and refuses rather than overrunning
   it. See whisper-budget for the decision, which is pure and tested; this atom
   only holds the facts it decides over."
  (atom {:ctx          {}
         :resident     {}
         :pinned       #{}
         :lru          []
         :budget-bytes nil}))

(def ^:private vram-reserve-bytes
  "Device memory left to everything that is not our weights: the CUDA context
   itself, the driver's own allocations, and whatever else shares the card. Free
   memory is read once, before anything is loaded, so this is the margin against
   that reading being optimistic."
  (* 512 1024 1024))

(defn- nvidia-free-bytes
  "Free device memory according to nvidia-smi, or nil when it cannot say: no
   binary, no driver, no GPU, or output we do not recognise. nil is a real
   answer here and means `do not pretend to know`, which the budget reads as no
   ceiling at all."
  []
  (try
    (let [{:keys [exit out]} (sh/sh "nvidia-smi" "--query-gpu=memory.free"
                                    "--format=csv,noheader,nounits")]
      (when (zero? exit)
        (some-> out str/split-lines first str/trim not-empty
                parse-long
                (* 1024 1024))))
    (catch Exception _ nil)))

(defn- configured-budget-bytes
  "An operator's explicit ceiling in MiB, from the
   `vtranslate.whisper.vram-budget-mb` system property or the
   VTRANSLATE_WHISPER_VRAM_BUDGET_MB environment variable. Wins over the probe,
   because a card shared by time-slicing or partitioned by MIG reports far more
   free memory than this process may actually take. 0 disables the ceiling.
   => bytes | nil"
  []
  (some-> (or (System/getProperty "vtranslate.whisper.vram-budget-mb")
              (System/getenv "VTRANSLATE_WHISPER_VRAM_BUDGET_MB"))
          str/trim not-empty parse-long (* 1024 1024)))

(defn- resolve-budget!
  "The device ceiling for GPU contexts, measured ONCE and remembered. A CPU
   context is bounded by host RAM rather than by a device, so it gets no
   ceiling and the old never-evict behaviour. => bytes (0 means no ceiling)"
  [use-gpu?]
  (or (:budget-bytes @ctx-cache)
      (let [b (if-not use-gpu?
                0
                (or (configured-budget-bytes)
                    (some-> (nvidia-free-bytes) (- vram-reserve-bytes) (max 0))
                    0))]
        (swap! ctx-cache assoc :budget-bytes b)
        b)))

(defn- model-bytes
  "Size of the weights file, or 0 when it cannot be read (the init that follows
   will fail on its own terms, and a 0 here never causes a false refusal)."
  [model-path]
  (try
    (let [f (java.io.File. ^String model-path)]
      (if (.isFile f) (.length f) 0))
    (catch Exception _ 0)))

(defn- free-context!
  "Give `k`'s weights back to the device. Called only for a context the plan
   chose, which is never one with a decode in flight."
  [^WhisperJNI w state k]
  (when-let [^WhisperContext ctx (get-in state [:ctx k])]
    (try (.free w ctx)
         (catch Throwable _
           ;; A context that will not free is a leak, not a reason to fail the
           ;; decode that is about to happen; the accounting drops it either way.
           nil))))

(defn- acquire-context!
  "The cached WhisperContext for `model-path` (+ GPU flag), loading it if it is
   not resident, and PINNED against eviction until `release-context!` is called
   with the same key. => [key ^WhisperContext]

   The whole get-or-load runs under `locking`, as it did when contexts were
   never freed: what changed is that loading may first give other contexts back
   to the device, and may refuse outright. Refusing is the point. whisper.cpp
   does not return nil when weights do not fit, it aborts the JVM inside
   libggml, so admission is decided BEFORE init from numbers we can read, and
   `init` is never reached when the plan says no.

   Admission uses an ESTIMATE (the weights file plus a margin). What the ledger
   then records is the MEASURED cost: nvidia-smi either side of the load says
   what the device actually gave up, and that is what the next admission
   reasons over. The larger of the two is kept, because a concurrent process
   releasing memory mid-load would otherwise make the measurement read low, and
   reading low is the direction that kills the JVM."
  [^WhisperJNI w model-path use-gpu?]
  (let [k      [model-path (boolean use-gpu?)]
        budget (resolve-budget! use-gpu?)]
    (locking ctx-cache
      (let [want  (budget/cost (model-bytes model-path))
            state (assoc @ctx-cache :budget-bytes budget)
            plan  (budget/plan state k want)]
        (when-not (:admit? plan)
          (throw (ex-info (budget/explain state k want plan)
                          (merge {:model-path model-path
                                  :use-gpu?   use-gpu?
                                  :budget-bytes budget
                                  :wanted-bytes want}
                                 (select-keys plan [:shortfall :reason])))))
        (doseq [victim (:evict plan)]
          (free-context! w @ctx-cache victim)
          (swap! ctx-cache (fn [s] (-> (budget/forget s [victim])
                                       (update :ctx dissoc victim)))))
        (let [ctx (or (get-in @ctx-cache [:ctx k])
                      (let [^WhisperContextParams params (WhisperContextParams.)
                            _      (set! (.-useGPU params) (boolean use-gpu?))
                            before (when use-gpu? (nvidia-free-bytes))
                            c      (.init w (Path/of ^String model-path (into-array String []))
                                          params)]
                        (when (nil? c)
                          (throw (ex-info "whisper init returned no context (bad/corrupt model or insufficient VRAM/RAM)"
                                          {:model-path model-path :use-gpu? use-gpu?})))
                        (let [after (when use-gpu? (nvidia-free-bytes))
                              took  (when (and before after) (- before after))
                              held  (max (long want) (long (or took 0)))]
                          (swap! ctx-cache (fn [s] (-> (budget/admit s k held)
                                                       (assoc-in [:ctx k] c)))))
                        c))]
          (swap! ctx-cache (fn [s] (-> (update s :lru budget/touch k)
                                       (update :pinned conj k))))
          [k ctx])))))

(defn- release-context!
  "Let `k` be evictable again. Balanced with `acquire-context!` in a finally, so
   a decode that throws does not strand the weights it was using."
  [k]
  (swap! ctx-cache update :pinned disj k)
  nil)

(defn lang-code
  "The language string whisper.cpp is handed. An explicit BCP-47 tag becomes its
   ISO-639-1 primary subtag, lower-cased (\"en-US\" -> \"en\"). nil/blank/\"auto\"
   => \"auto\", whisper's per-call language detection. Never nil: whisper-jni's
   WhisperFullParams defaults .language to \"en\", so leaving it unset forces
   English rather than detecting."
  [language]
  (or (some-> language str str/trim not-empty (str/split #"-") first str/lower-case not-empty)
      "auto"))

(defn default-threads
  "Threads to give whisper.cpp. Its own default is 4 regardless of the machine,
   which leaves most of a many-core host idle; two cores are held back for the
   JVM and the rest of the pipeline."
  []
  (max 1 (- (.availableProcessors (Runtime/getRuntime)) 2)))

(defn- apply-decode-opts!
  "Set on `params` exactly the knobs `resolved` holds, and no others. Effectful.
   => `resolved`."
  [^WhisperFullParams params resolved]
  (doseq [[knob v] resolved]
    (case knob
      :beam-size                   (set! (.-beamSearchBeamSize params) (int v))
      :best-of                     (set! (.-greedyBestOf params) (int v))
      :temperature                 (set! (.-temperature params) (float v))
      :temperature-inc             (set! (.-temperatureInc params) (float v))
      :entropy-thold               (set! (.-entropyThold params) (float v))
      :logprob-thold               (set! (.-logprobThold params) (float v))
      :no-speech-thold             (set! (.-noSpeechThold params) (float v))
      :suppress-blank?             (set! (.-suppressBlank params) (boolean v))
      :suppress-non-speech-tokens? (set! (.-suppressNonSpeechTokens params) (boolean v))
      :no-context?                 (set! (.-noContext params) (boolean v))
      :initial-prompt              (set! (.-initialPrompt params) ^String v)))
  resolved)

(defn transcribe-samples
  "Run whisper.cpp over `samples` (16 kHz mono float[]) via the cached context for
   `model-path`, returning RAW hypotheses for support/normalize-segments to shape.
   `language` nil/\"auto\" makes whisper detect the language of THIS call (see
   `lang-code`). `run-opts` may carry :threads, :print-progress? and any knob of
   `decode-knobs`; a knob it does not carry keeps whisper-jni's own default.
   => (r/ok [{:start-ms n :end-ms n :text s} ...]) | (r/err :error/asr-failed {...}).
   Fails loud: a non-zero `full` rc or any interop throw becomes :error/asr-failed,
   never a fake/empty transcript. Weights that do not fit the device are REFUSED
   the same way rather than loaded, because loading them aborts the JVM."
  ([model-path use-gpu? ^floats samples language]
   (transcribe-samples model-path use-gpu? samples language nil))
  ([model-path use-gpu? ^floats samples language run-opts]
   (r/try-effect* :error/asr-failed
     (when (nil? samples)
       (throw (ex-info "no audio samples (nil) — upstream audio decode produced nothing"
                       {:model-path model-path})))
     (let [^WhisperJNI w   @jni
           [k ^WhisperContext ctx] (acquire-context! w model-path use-gpu?)]
       (try
         (let [^WhisperFullParams params (WhisperFullParams.)
               ^String lang (lang-code language)
               threads (long (or (:threads run-opts) (default-threads)))]
           (set! (.-language params) lang)
           (set! (.-nThreads params) (int threads))
           ;; progress goes to stderr, which is the only signal a caller has that a
           ;; multi-minute transcription is alive
           (set! (.-printProgress params) (boolean (get run-opts :print-progress? true)))
           (set! (.-printRealtime params) false)
           (set! (.-printTimestamps params) false)
           (apply-decode-opts! params (sup/resolve-decode-opts run-opts))
           (locking ctx
             (let [rc (.full w ctx params samples (alength samples))]
               (when-not (zero? rc)
                 (throw (ex-info "whisper full() returned non-zero"
                                 {:rc rc :model-path model-path})))
               (let [n (.fullNSegments w ctx)]
                 (mapv (fn [i]
                         {:start-ms (* 10 (.fullGetSegmentTimestamp0 w ctx i))
                          :end-ms   (* 10 (.fullGetSegmentTimestamp1 w ctx i))
                          :text     (.fullGetSegmentText w ctx i)})
                       (range n))))))
         (finally
           (release-context! k)))))))
