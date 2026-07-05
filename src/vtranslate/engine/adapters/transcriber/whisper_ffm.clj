(ns vtranslate.engine.adapters.transcriber.whisper-ffm
  "ITranscriber over whisper.cpp bound DIRECTLY through the Panama Foreign
   Function & Memory API (java.lang.foreign) — the ZERO-DEPENDENCY native path
   (Path 4). No Maven artifact, no JNI shim: we bind libwhisper.so's symbols with
   a Linker + SymbolLookup and drive them via MethodHandles. Because the FFM API
   is on the JDK 25 CORE classpath, this whole file loads on the core cp with the
   native dep ABSENT — mirroring the collect.port -> collect.ffmpeg split, except
   the split is DEGENERATE here: there is no optional class to import, only an
   optional .so to open, so we stay single-file. The ONLY thing gated on the
   backend being present is the libraryLookup, done lazily inside resolve/transcribe.

   CORE-SAFE LOADING: every top-level :import is a JDK class (java.lang.foreign.*,
   java.lang.invoke.MethodHandle) — always resolvable on JDK 22+. No native call
   fires at load time; Linker/libraryLookup/downcall-invocation/reinterpret (all
   RESTRICTED methods, needing --enable-native-access at runtime) run only after
   the availability probe passes. So the ns loads even with no libwhisper.so.

   CAPABILITY GATE at RESOLVE time (not call time): resolve-transcriber :whisper-ffm
   yields :error/transcriber-unavailable unless -Dvtranslate.whisper.lib (or config
   [:transcriber-opts :lib-path]) points at a libwhisper.so that EXISTS — so the
   router's fallback chain skips this backend cleanly instead of exploding mid-call.
   transcribe FAILS LOUD (:error/asr-failed) on any bind/decode/model failure —
   never a fake or empty transcript.

   THE BY-VALUE STRUCT HAZARD (why there is no field modeling here):
   whisper.cpp's public API passes `struct whisper_full_params` BY VALUE into
   whisper_full and RETURNS it by value from whisper_full_default_params. That
   struct is a large (hundreds of bytes), version-churning aggregate of ints,
   floats, bools, callbacks and nested sub-structs — hand-modeling its FFM layout
   field-by-field is both a maintenance sink and a segfault waiting to happen the
   moment the header drifts. We DON'T model it. It is a >16-byte aggregate, i.e.
   MEMORY class under the x86-64 System V ABI, so it is passed/returned purely by
   SIZE + alignment (register classification is irrelevant for MEMORY-class
   aggregates). We therefore stand it in with an OPAQUE, 8-byte-aligned blob whose
   size OVER-ESTIMATES sizeof(whisper_full_params). Over-sizing is provably safe on
   BOTH ends because we pass through the defaults UNCHANGED:
     - RETURN: the struct-returning downcall allocates our N-byte segment and the
       callee writes only the real sizeof (<= N) into it — the slack is untouched.
     - ARG:    the by-value copy reads N bytes out of a segment WE fully own (our
       N-byte allocation), so we never over-read the callee's heap; the callee in
       turn reads only the real sizeof from its stack copy — the slack is ignored.
   The consequence of NOT modeling fields: we can't set params.language, so whisper
   runs with its built-in default (see notes) — language is accepted but unused."
  (:require [clojure.string :as str]
            [hive-dsl.result :as r]
            [vtranslate.engine.port.transcriber :as p.asr]
            [vtranslate.engine.adapters.transcriber.support :as sup]
            [vtranslate.engine.providers.transcriber-registry :as reg])
  (:import [java.lang.foreign Linker Linker$Option SymbolLookup FunctionDescriptor
                              Arena MemorySegment MemoryLayout ValueLayout]
           [java.lang.invoke MethodHandle]
           [java.io File]))

;; whisper_sampling_strategy::WHISPER_SAMPLING_GREEDY — the cheapest decoder and
;; the default whisper_full_default_params expects. We pass it through as the
;; enum's int ordinal (FFM has no enum notion).
(def ^:private ^:const strategy-greedy 0)

;; Default opaque size for `struct whisper_full_params`. A generous over-estimate
;; (safe per the by-value analysis in the ns docstring) that comfortably exceeds
;; every historical sizeof — only widen it via config if a future whisper.cpp
;; grows the struct past this. NEVER an under-estimate: too-small corrupts the
;; return (callee writes past our allocation).
(def ^:private ^:const params-bytes-default 1024)

(defn- opaque-params-layout
  "An opaque, 8-byte-aligned GroupLayout of >= `n-bytes` standing in for
   `struct whisper_full_params`. It MUST be a GroupLayout (not a bare sequence) so
   FFM applies the MEMORY-class by-value convention (leading SegmentAllocator on
   the returning downcall; stack copy on the arg). 8-byte alignment matches the
   struct's pointer/int64 members; size is rounded up to whole longs."
  ^MemoryLayout [n-bytes]
  (let [n-longs (quot (+ (long n-bytes) 7) 8)]
    (MemoryLayout/structLayout
     (into-array MemoryLayout
                 [(MemoryLayout/sequenceLayout n-longs ValueLayout/JAVA_LONG)]))))

(defn- bind-lib
  "Open `lib-path` under a PROCESS-LIFETIME shared Arena and build the whisper
   downcall MethodHandles. => {:handles {sym MethodHandle} :params-layout ...}.
   FAILS LOUD (throws) if the library or ANY required symbol is missing — the
   caller wraps this in r/try-effect* so a bad build surfaces as :error/asr-failed
   rather than a half-bound record. The shared Arena is intentionally never
   closed: the handles + symbol segments live for the JVM's lifetime (cached)."
  [lib-path params-bytes]
  (let [arena  (Arena/ofShared)
        linker (Linker/nativeLinker)
        lookup (SymbolLookup/libraryLookup ^String lib-path arena)
        A      ValueLayout/ADDRESS
        I      ValueLayout/JAVA_INT
        L      ValueLayout/JAVA_LONG
        params (opaque-params-layout params-bytes)
        no-opt (make-array Linker$Option 0)
        dc     (fn [sym ^FunctionDescriptor fd]
                 (let [seg (.orElseThrow (.find lookup ^String sym))]
                   (.downcallHandle linker ^MemorySegment seg fd no-opt)))]
    {:params-layout params
     :handles
     {;; whisper_context* whisper_init_from_file(const char* path_model)
      :init  (dc "whisper_init_from_file"        (FunctionDescriptor/of A (into-array MemoryLayout [A])))
      ;; struct whisper_full_params whisper_full_default_params(enum strategy)  [BY-VALUE RETURN]
      :dfp   (dc "whisper_full_default_params"   (FunctionDescriptor/of params (into-array MemoryLayout [I])))
      ;; int whisper_full(ctx, struct whisper_full_params params, const float*, int)  [BY-VALUE ARG]
      :full  (dc "whisper_full"                  (FunctionDescriptor/of I (into-array MemoryLayout [A params A I])))
      ;; int whisper_full_n_segments(ctx)
      :nseg  (dc "whisper_full_n_segments"       (FunctionDescriptor/of I (into-array MemoryLayout [A])))
      ;; const char* whisper_full_get_segment_text(ctx, i)
      :text  (dc "whisper_full_get_segment_text" (FunctionDescriptor/of A (into-array MemoryLayout [A I])))
      ;; int64_t whisper_full_get_segment_t0(ctx, i)   [CENTISECONDS]
      :t0    (dc "whisper_full_get_segment_t0"   (FunctionDescriptor/of L (into-array MemoryLayout [A I])))
      ;; int64_t whisper_full_get_segment_t1(ctx, i)   [CENTISECONDS]
      :t1    (dc "whisper_full_get_segment_t1"   (FunctionDescriptor/of L (into-array MemoryLayout [A I])))
      ;; void whisper_free(ctx)
      :free  (dc "whisper_free"                  (FunctionDescriptor/ofVoid (into-array MemoryLayout [A])))}}))

;; Bound-library cache keyed by [lib-path params-bytes]: binding opens a shared
;; Arena + resolves 8 symbols, so we do it ONCE per distinct backend. A throwing
;; bind-lib never pollutes the cache (swap! only commits on success).
(defonce ^:private lib-cache (atom {}))

(defn- bound-lib
  "Cached bind-lib. Throws (fail loud) on a missing lib/symbol."
  [lib-path params-bytes]
  (let [k [lib-path params-bytes]]
    (or (get @lib-cache k)
        (get (swap! lib-cache assoc k (bind-lib lib-path params-bytes)) k))))

(defn- seg-text
  "Read the NUL-terminated UTF-8 C string a `whisper_full_get_segment_text` return
   points at. The downcall hands back a zero-length ADDRESS segment, so we
   reinterpret it to a large upper bound (RESTRICTED) before getString scans to the
   NUL. Segment texts are short; the bound only caps a runaway scan."
  ^String [^MemorySegment ptr]
  (.getString (.reinterpret ptr (long Integer/MAX_VALUE)) 0))

(defn- run-whisper
  "Effectful core: init a context from `model-path`, decode `samples` with default
   (greedy) params, and project each whisper segment to a raw contract map
   {:start-ms :end-ms :text}. t0/t1 are CENTISECONDS, so *10 -> ms. FAILS LOUD via
   thrown ex-info (NULL context, non-zero whisper_full rc); the caller's
   r/try-effect* maps the throw onto :error/asr-failed. Time/alignment/space
   correctness: samples + the C model-path + the returned params struct all live in
   a CONFINED per-call Arena closed on exit; the whisper_context is NOT arena-owned
   (whisper mallocs it), so it is whisper_free'd in a finally BEFORE the arena
   closes — no leak on the happy path OR on throw."
  [{:keys [handles]} model-path ^floats samples]
  (with-open [arena (Arena/ofConfined)]
    (let [h        (fn [k] ^MethodHandle (get handles k))
          model-c  (.allocateFrom arena ^String model-path)
          ctx      ^MemorySegment (.invokeWithArguments (h :init) (object-array [model-c]))]
      (when (or (nil? ctx) (zero? (.address ctx)))
        (throw (ex-info "whisper_init_from_file returned NULL (bad/missing model)"
                        {:model-path model-path})))
      (try
        (let [n-samp   (alength samples)
              samp-seg (.allocateFrom arena ValueLayout/JAVA_FLOAT samples)
              ;; leading arg is the SegmentAllocator FFM uses for the by-value return
              params   (.invokeWithArguments (h :dfp) (object-array [arena (int strategy-greedy)]))
              rc       (int (.invokeWithArguments (h :full)
                                                  (object-array [ctx params samp-seg (int n-samp)])))]
          (when-not (zero? rc)
            (throw (ex-info "whisper_full failed" {:rc rc :n-samples n-samp})))
          (let [n-seg (int (.invokeWithArguments (h :nseg) (object-array [ctx])))]
            (mapv (fn [i]
                    (let [i*  (int i)
                          t0  (long (.invokeWithArguments (h :t0) (object-array [ctx i*])))
                          t1  (long (.invokeWithArguments (h :t1) (object-array [ctx i*])))
                          txt (seg-text (.invokeWithArguments (h :text) (object-array [ctx i*])))]
                      {:start-ms (* 10 t0)         ; centiseconds -> milliseconds
                       :end-ms   (* 10 t1)
                       :text     txt}))
                  (range n-seg))))
        (finally
          (.invokeWithArguments (h :free) (object-array [ctx])))))))

(defrecord WhisperFfmTranscriber [lib-path model-path params-bytes]
  p.asr/ITranscriber
  (transcribe [_ audio-source _language _opts]
    ;; _language is accepted but not applied: setting params.language requires
    ;; writing into the struct, which we deliberately don't model (see ns docstring)
    ;; — whisper decodes with its built-in default language.
    (if-let [path (sup/audio->path audio-source)]
      (if (str/blank? (str model-path))
        (r/err :error/asr-failed
               {:reason "no whisper model path (config [:transcriber-opts :model-path] or -Dvtranslate.whisper.model)"})
        (r/let-ok [wav (sup/read-wav-mono-floats path)
                   raw (r/try-effect* :error/asr-failed
                         (run-whisper (bound-lib lib-path params-bytes) model-path (:samples wav)))]
          ;; funnel through support/normalize-segments — the LSP guardrail: ordered,
          ;; non-overlapping, start<=end, non-blank text, BY CONSTRUCTION.
          (r/ok {:segments (sup/normalize-segments raw {:unit :ms})})))
      (r/err :error/asr-failed {:reason "audio-source carries no path"}))))

;; --- provider registry ------------------------------------------------------

(defn- lib-path-from [config]
  (or (get-in config [:transcriber-opts :lib-path])
      (System/getProperty "vtranslate.whisper.lib")))

(defn- model-path-from [config]
  (or (get-in config [:transcriber-opts :model-path])
      (System/getProperty "vtranslate.whisper.model")))

(defn- params-bytes-from [config]
  (or (get-in config [:transcriber-opts :params-struct-bytes])
      (some-> (System/getProperty "vtranslate.whisper.params-bytes") Long/parseLong)
      params-bytes-default))

(defn- lib-available?
  "The RESOLVE-time capability probe: a configured lib path that names an existing
   file. Absent/missing -> the backend is unavailable and the router falls through."
  [lib-path]
  (boolean (and (not (str/blank? (str lib-path)))
                (.isFile (File. ^String lib-path)))))

(defmethod reg/resolve-transcriber :whisper-ffm
  [_ config]
  (let [lib-path (lib-path-from config)]
    (if (lib-available? lib-path)
      (r/ok (->WhisperFfmTranscriber lib-path (model-path-from config) (params-bytes-from config)))
      (r/err :error/transcriber-unavailable
             {:provider :whisper-ffm
              :hint     "set -Dvtranslate.whisper.lib=/abs/libwhisper.so (or config [:transcriber-opts :lib-path]) to an EXISTING shared library; build whisper.cpp with -DBUILD_SHARED_LIBS=ON and run with --enable-native-access=ALL-UNNAMED"}))))
