(ns vtranslate.engine.adapters.transcriber.onnx-bytedeco-native
  "NATIVE half of the :onnx-bytedeco adapter — the ONLY ns in this backend that
   (:import ...)s org.bytedeco.onnxruntime.*. It is loaded LAZILY (requiring-resolve
   from onnx_bytedeco.clj) and ONLY after that ns's gate proved the classes are on
   the classpath, so the engine core stays loadable with the :onnx alias absent —
   the exact split collect.ffmpeg has from collect.port.

   WHAT IS REAL HERE: the ONNX Runtime session plumbing over bytedeco's C++ Ort::
   wrappers — Env/SessionOptions lifecycle, opening a Session from a model file,
   enumerating IO names, building a float input tensor, running a single-input graph
   and reading its output floats. Those are the load-bearing primitives a Whisper
   decode is assembled FROM.

   WHAT IS NOT BUILT (the marked integration points): (a) the 80-bin log-mel front
   end, (b) the autoregressive DECODER loop that steps decoder.onnx token-by-token,
   (c) the BPE tokenizer/detokenizer + timestamp-token -> segment projection. Until
   those exist, `transcribe-wav` FAILS LOUD with :error/asr-failed — it NEVER emits
   a fabricated or empty transcript. The real primitives below are the fill-in
   surface: wire steps (a)-(c) and swap the honest short-circuit for the pipeline.

   INTEROP CAUTION: method/overload names below target the bytedeco onnxruntime
   1.18 presets and are UNVERIFIED in this session (the dep is absent). If any is
   off, requiring this ns throws at compile time and the caller degrades it to a
   LOUD :error/asr-failed (see onnx_bytedeco.clj) — a safe failure, never a fake."
  (:require [hive-dsl.result :as r]
            [vtranslate.engine.adapters.transcriber.support :as sup])
  (:import [org.bytedeco.onnxruntime Env SessionOptions Session RunOptions
                                     MemoryInfo Value AllocatorWithDefaultOptions
                                     ValueVector]
           [org.bytedeco.onnxruntime.global onnxruntime]
           [org.bytedeco.javacpp BytePointer FloatPointer LongPointer PointerPointer]))

;; --- ORT environment + session lifecycle (REAL) -----------------------------

(def ^:private ort-env
  "The process-wide Ort::Env. A `delay` so merely REQUIRING this ns (which happens
   at activation, before the pipeline is wired) neither constructs the environment
   nor dlopens libonnxruntime — the native library is touched only when a real
   transcribe first forces a session. logid 'vtranslate' tags ORT's own log lines."
  (delay (Env. onnxruntime/ORT_LOGGING_LEVEL_WARNING (BytePointer. "vtranslate" "UTF-8"))))

(defn- make-session-options
  "Fresh SessionOptions. Single intra-op thread by default (the pipeline layer owns
   parallelism across clips — one session-per-call, mirroring the ffmpeg grabber
   discipline — so per-session threads would oversubscribe). Extended graph
   optimization trades a little load time for faster steady-state inference, which
   matters because the decoder session is stepped once PER TOKEN."
  ^SessionOptions []
  (doto (SessionOptions.)
    (.SetIntraOpNumThreads 1)
    (.SetGraphOptimizationLevel onnxruntime/ORT_ENABLE_EXTENDED)))

(defn- open-session*
  "Open an Ort::Session over the model file at `path`. On Linux/macOS the char*
   (BytePointer) constructor overload is correct; a Windows build would need the
   wchar_t overload. Throws on a missing/corrupt model — the caller turns that into
   a LOUD :error/asr-failed rather than a fake transcript."
  ^Session [^String path]
  (Session. ^Env @ort-env (BytePointer. path "UTF-8") (make-session-options)))

(def ^:private session-cache
  "Path -> open Session. Sessions are EXPENSIVE (parse + optimize the graph, alloc
   arenas) and immutable once built, so we open each model file at most once and
   share it. Not thread-safe to run concurrently on ONE session for stateful graphs,
   but Whisper encoder/decoder graphs are stateless per Run — the same discipline
   ORT's own C++ examples rely on. atom+swap! is the cacheless-registry pattern used
   elsewhere in the engine, scoped here to native handles."
  (atom {}))

(defn session
  "Get-or-open the Session for `path`, memoized in `session-cache`."
  ^Session [^String path]
  (if-let [s (get @session-cache path)]
    s
    (get (swap! session-cache (fn [m] (cond-> m (not (get m path)) (assoc path (open-session* path))))) path)))

(defn io-names
  "The graph's input and output tensor names — needed to key Session.Run. ORT hands
   names out through an allocator; we copy them into Clojure strings immediately so
   nothing dangles on the native allocator. => {:inputs [name...] :outputs [name...]}."
  [^Session s]
  (let [alloc (AllocatorWithDefaultOptions.)
        pull  (fn [n get-name]
                (mapv (fn [i]
                        (-> ^Session s (get-name (long i) alloc) (.get) (.getString)))
                      (range n)))]
    {:inputs  (pull (.GetInputCount s)  (fn [^Session s i a] (.GetInputNameAllocated s i a)))
     :outputs (pull (.GetOutputCount s) (fn [^Session s i a] (.GetOutputNameAllocated s i a)))}))

;; --- tensors (REAL) ---------------------------------------------------------

(def ^:private cpu-memory-info
  "CPU allocator MemoryInfo, reused for every input tensor (it only names WHERE the
   backing memory lives). Arena allocator + default mem type = ordinary host RAM."
  (delay (MemoryInfo/CreateCpu onnxruntime/OrtArenaAllocator onnxruntime/OrtMemTypeDefault)))

(defn float-tensor
  "Wrap a Clojure float-array `data` as an ORT float32 input Value of `shape`
   (a long-seq, row-major). The FloatPointer VIEWS `data`, so the array must outlive
   the Run call — fine within a single transcribe. `p_data_element_count` is the
   ELEMENT count (not bytes); we assert it matches the shape product so a mis-shaped
   mel fails here, not deep in native code."
  ^Value [^floats data shape]
  (let [n      (alength data)
        prod   (long (reduce * 1 shape))
        _      (when (not= n prod)
                 (throw (ex-info "float-tensor: data length != shape product"
                                 {:data-len n :shape (vec shape) :product prod})))
        fp     (FloatPointer. data)
        shp    (LongPointer. (long-array shape))]
    (Value/CreateTensor ^MemoryInfo @cpu-memory-info fp (long n) shp (long (count shape)))))

(defn tensor->floats
  "Copy an ORT float32 output tensor into a Clojure float-array. GetElementCount +
   the typed data accessor; we snapshot into the array so the result survives the
   ValueVector being released."
  ^floats [^Value v]
  (let [info (.GetTensorTypeAndShapeInfo v)
        n    (.GetElementCount info)
        fp   ^FloatPointer (.GetTensorMutableDataFloat v)
        out  (float-array n)]
    (.get (.capacity fp n) out)
    out))

(defn run1
  "Run a SINGLE-input / SINGLE-output graph: `s`.Run(in-name=in-tensor) -> output
   floats. Sufficient for the encoder (one 'mel' input -> one hidden-state output).
   The multi-input DECODER step (input_ids + the encoder hidden states + KV cache)
   needs the array-of-Values Run overload and is one of the marked TODOs below."
  ^floats [^Session s ^String in-name ^Value in-tensor ^String out-name]
  (let [run-opts (RunOptions.)
        ins      (doto (PointerPointer. 1) (.put 0 (BytePointer. in-name "UTF-8")))
        outs     (doto (PointerPointer. 1) (.put 0 (BytePointer. out-name "UTF-8")))
        ^ValueVector res (.Run s run-opts ins in-tensor 1 outs 1)]
    (tensor->floats (.get res 0))))

;; --- log-mel front end (INTEGRATION POINT (a) — NOT BUILT) -------------------

(def whisper-mel-spec
  "The exact Whisper log-mel contract the encoder input expects. Kept as DATA so the
   implementer wires against fixed numbers, not folklore. n-mels=80 for the base/
   small/medium/large-v1/2 exports (large-v3 uses 128). The 30 s window => 3000
   frames at hop 160 on 16 kHz; shorter clips are zero-padded, longer clips chunked."
  {:sample-rate 16000 :n-fft 400 :hop 160 :n-mels 80 :chunk-frames 3000 :window :hann})

(defn log-mel-spectrogram
  "TODO (integration point a): 16 kHz mono float samples -> [1 n-mels chunk-frames]
   log-mel, per `whisper-mel-spec`. Steps: Hann-windowed STFT (n-fft 400, hop 160)
   -> power spectrum -> apply the precomputed 80x201 mel filterbank (ship it as an
   .npz/edn resource under model-dir; NOT derivable cheaply at runtime) -> log10,
   clamp to (max - 8.0), scale (+4)/4. Then pad/chunk to 3000 frames and flatten
   row-major for `float-tensor`. Throws until built so no caller silently proceeds
   on a bogus (e.g. all-zero) mel."
  [^floats _samples]
  (throw (ex-info "onnx-bytedeco log-mel front end not yet implemented"
                  {:need :mel-filterbank :spec whisper-mel-spec})))

;; --- the pipeline entrypoint ------------------------------------------------

(defn transcribe-wav
  "Entrypoint the OUTER record delegates to. `model-dir` holds encoder.onnx +
   decoder.onnx + tokenizer.json; `path` is the 16 kHz mono PCM WAV; `language` a
   BCP-47 tag; `opts` the merged config/call opts. Returns the ITranscriber Result.

   The full pipeline, once the three integration points are filled, is:

     (r/let-ok [{:keys [samples]} (sup/read-wav-mono-floats path)          ; DONE (support)
                mel   (log-mel-spectrogram samples)]                        ; TODO (a)
       (let [enc-h  (run1 (session (str model-dir \"/encoder.onnx\"))       ; REAL (run1)
                          \"mel\" (float-tensor mel (whisper-shape)) \"output\")
             tokens (decode-loop (session (str model-dir \"/decoder.onnx\")) ; TODO (b)
                                 enc-h (sot-prompt language))                ;   argmax over
             raw    (detokenize (load-bpe (str model-dir \"/tokenizer.json\")) ; TODO (c)
                                tokens)]                                     ;   -> [{:start :end :text}...] seconds
         (r/ok {:segments (sup/normalize-segments raw {:unit :s})})))       ; DONE (support guardrail)

   Steps (a) log-mel, (b) the autoregressive decoder loop, and (c) the BPE
   tokenizer + <|t_xx.xx|> timestamp-token -> segment projection are NOT built, so
   we short-circuit with a contract-valid failure BEFORE touching the model — no
   half-run, no fabricated transcript. This is the ONE line to replace when wiring
   the fill-in above; the raw segments MUST funnel through sup/normalize-segments
   (the LSP guardrail) so the output is ordered/non-overlapping/start<=end by
   construction, exactly like every sibling adapter."
  [model-dir path _language _opts]
  (r/err :error/asr-failed
         {:reason    "onnx-bytedeco decode pipeline not yet implemented"
          :model-dir model-dir
          :todo      [:log-mel :decoder-loop :bpe-tokenizer]}))
