(ns vtranslate.engine.adapters.transcriber.onnx-bytedeco-native
  "NATIVE half of the :onnx-bytedeco adapter — the ONLY ns in this backend that
   (:import ...)s org.bytedeco.onnxruntime.*. It is loaded LAZILY (requiring-resolve
   from onnx_bytedeco.clj) and ONLY after that ns's gate proved the classes are on
   the classpath, so the engine core stays loadable with the :onnx alias absent —
   the exact split collect.ffmpeg has from collect.port.

   This namespace owns the complete cacheless Whisper ONNX path: Slaney log-mel,
   encoder execution, greedy autoregressive decoding, byte-level BPE, timestamp
   projection, and shared segment normalization.

   INTEROP CAUTION: method/overload names below target the bytedeco onnxruntime
   1.28 / JavaCPP 1.5.14 presets. If any model-specific graph contract is
   off, requiring this ns throws at compile time and the caller degrades it to a
   LOUD :error/asr-failed (see onnx_bytedeco.clj) — a safe failure, never a fake."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [hive-dsl.result :as r]
            [vtranslate.engine.adapters.transcriber.support :as sup])
  (:import [org.bytedeco.onnxruntime Env SessionOptions Session RunOptions
                                     OrtAllocator Value AllocatorWithDefaultOptions
                                     ValueVector]
           [org.bytedeco.onnxruntime.global onnxruntime]
           [org.bytedeco.javacpp BytePointer FloatPointer LongPointer PointerPointer]
           [org.jtransforms.fft FloatFFT_1D]
           [java.io ByteArrayOutputStream]
           [java.nio.charset StandardCharsets]))

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
  (let [owned-alloc (AllocatorWithDefaultOptions.)
        alloc (OrtAllocator. (.asUnownedAllocator owned-alloc))
        pull  (fn [n get-name]
                (mapv (fn [i]
                        (.getString ^BytePointer (get-name s (long i) alloc)))
                      (range n)))]
    {:inputs  (pull (.GetInputCount s)  (fn [^Session s i a] (.GetInputNameAllocated s i a)))
     :outputs (pull (.GetOutputCount s) (fn [^Session s i a] (.GetOutputNameAllocated s i a)))}))

;; --- tensors (REAL) ---------------------------------------------------------

(def ^:private cpu-allocator
  (delay (AllocatorWithDefaultOptions.)))

(defn- ort-allocator ^OrtAllocator []
  (OrtAllocator. (.asUnownedAllocator ^AllocatorWithDefaultOptions @cpu-allocator)))

(defn float-tensor
  "Copy float-array `data` into an allocator-owned ORT tensor of `shape`."
  ^Value [^floats data shape]
  (let [n      (alength data)
        prod   (long (reduce * 1 shape))
        _      (when (not= n prod)
                 (throw (ex-info "float-tensor: data length != shape product"
                                 {:data-len n :shape (vec shape) :product prod})))
        ^longs shape (long-array shape)
        tensor (Value/CreateTensorFloat (ort-allocator) shape (long (alength shape)))]
    (.put ^FloatPointer (.GetTensorMutableDataFloat tensor) data)
    tensor))

(defn long-tensor
  ^Value [values shape]
  (let [^longs data (long-array values)
        ^longs shape (long-array shape)
        prod (long (reduce * 1 shape))]
    (when (not= (alength data) prod)
      (throw (ex-info "long-tensor: data length != shape product"
                      {:data-len (alength data) :shape (vec shape) :product prod})))
    (let [tensor (Value/CreateTensorLong (ort-allocator) shape (long (alength shape)))]
      (.put ^LongPointer (.GetTensorMutableDataLong tensor) data)
      tensor)))

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

(defn tensor-shape [^Value v]
  (let [info (.GetTensorTypeAndShapeInfo v)
        dims (long-array (.GetDimensionsCount info))]
    (.GetDimensions info dims (alength dims))
    (vec dims)))

(defn tensor-data [^Value v]
  {:data (tensor->floats v)
   :shape (tensor-shape v)})

(defn- name-pointers [names]
  (let [pointers (PointerPointer. (long (count names)))]
    (doseq [[index name] (map-indexed vector names)]
      (.put pointers (long index) (BytePointer. ^String name "UTF-8")))
    pointers))

(defn run-graph
  "Run a graph with named input Values and copy the requested float outputs."
  [^Session s inputs output-names]
  (let [inputs (vec inputs)
        values (Value. (long (count inputs)))
        _ (doseq [[index [_ value]] (map-indexed vector inputs)]
            (.put (.position values (long index)) ^Value value))
        _ (.position values 0)
        ^ValueVector result (.Run s (RunOptions.)
                                  (name-pointers (mapv first inputs)) values
                                  (long (count inputs))
                                  (name-pointers output-names)
                                  (long (count output-names)))]
    (mapv (fn [index] (tensor-data (.get result (long index))))
          (range (count output-names)))))

(defn run1
  "Run a SINGLE-input / SINGLE-output graph: `s`.Run(in-name=in-tensor) -> output
   floats. The decoder uses the general `run-graph` path."
  ^floats [^Session s ^String in-name ^Value in-tensor ^String out-name]
  (let [run-opts (RunOptions.)
        ins      (doto (PointerPointer. 1) (.put 0 (BytePointer. in-name "UTF-8")))
        outs     (doto (PointerPointer. 1) (.put 0 (BytePointer. out-name "UTF-8")))
        ^ValueVector res (.Run s run-opts ins in-tensor 1 outs 1)]
    (tensor->floats (.get res 0))))

;; --- log-mel front end -------------------------------------------------------

(def whisper-mel-spec
  "The exact Whisper log-mel contract the encoder input expects. Kept as DATA so the
   implementer wires against fixed numbers, not folklore. n-mels=80 for the base/
   small/medium/large-v1/2 exports (large-v3 uses 128). The 30 s window => 3000
   frames at hop 160 on 16 kHz; shorter clips are zero-padded, longer clips chunked."
  {:sample-rate 16000 :n-fft 400 :hop 160 :n-mels 80 :chunk-frames 3000 :window :hann})

(defn- hz->mel [hz]
  (if (< hz 1000.0)
    (/ hz (/ 200.0 3.0))
    (+ 15.0 (/ (Math/log (/ hz 1000.0)) (/ (Math/log 6.4) 27.0)))))

(defn- mel->hz [mel]
  (if (< mel 15.0)
    (* mel (/ 200.0 3.0))
    (* 1000.0 (Math/exp (* (/ (Math/log 6.4) 27.0) (- mel 15.0))))))

(defn- mel-filterbank [sample-rate n-fft n-mels]
  (let [bins (inc (quot n-fft 2))
        low (hz->mel 0.0)
        high (hz->mel (/ sample-rate 2.0))
        points (mapv #(mel->hz (+ low (* (/ (- high low) (inc n-mels)) %)))
                     (range (+ n-mels 2)))
        filters (float-array (* n-mels bins))]
    (dotimes [m n-mels]
      (let [left (nth points m)
            center (nth points (inc m))
            right (nth points (+ m 2))
            norm (/ 2.0 (- right left))]
        (dotimes [k bins]
          (let [freq (* k (/ (double sample-rate) n-fft))
                weight (cond
                         (<= left freq center) (/ (- freq left) (- center left))
                         (< center freq right) (/ (- right freq) (- right center))
                         :else 0.0)]
            (aset-float filters (+ (* m bins) k) (float (* norm weight)))))))
    filters))

(def ^:private whisper-window
  (delay
    (let [{:keys [n-fft]} whisper-mel-spec
          window (float-array n-fft)]
      (dotimes [i n-fft]
        (aset-float window i
                    (float (- 0.5 (* 0.5 (Math/cos (/ (* 2.0 Math/PI i) n-fft)))))))
      window)))

(def ^:private whisper-filters
  (delay
    (let [{:keys [sample-rate n-fft n-mels]} whisper-mel-spec]
      (mel-filterbank sample-rate n-fft n-mels))))

(defn- reflected-sample [^floats samples index]
  (let [length (alength samples)
        index (cond
                (neg? index) (- index)
                (>= index length) (- (* 2 length) index 2)
                :else index)]
    (if (or (neg? index) (>= index length))
      0.0
      (aget samples index))))

(defn log-mel-spectrogram
  "Whisper-compatible Slaney log-mel tensor, flattened as [1,80,3000]."
  [^floats samples]
  (let [{:keys [n-fft hop n-mels chunk-frames]} whisper-mel-spec
        chunk-size (* hop chunk-frames)
        padded (float-array chunk-size)
        _ (System/arraycopy samples 0 padded 0 (min chunk-size (alength samples)))
        bins (inc (quot n-fft 2))
        fft (FloatFFT_1D. (long n-fft))
        spectrum (float-array (* 2 n-fft))
        power (float-array bins)
        mel (float-array (* n-mels chunk-frames))
        window ^floats @whisper-window
        filters ^floats @whisper-filters]
    (dotimes [frame chunk-frames]
      (java.util.Arrays/fill spectrum (float 0.0))
      (dotimes [i n-fft]
        (aset-float spectrum (* 2 i)
                    (float (* (reflected-sample padded
                                                (+ (* frame hop) i (- (quot n-fft 2))))
                              (aget window i)))))
      (.complexForward fft spectrum)
      (dotimes [k bins]
        (let [re (aget spectrum (* 2 k))
              im (aget spectrum (inc (* 2 k)))]
          (aset-float power k (float (+ (* re re) (* im im))))))
      (dotimes [m n-mels]
        (loop [k 0, energy 0.0]
          (if (= k bins)
            (aset-float mel (+ (* m chunk-frames) frame)
                        (float (Math/log10 (max 1.0e-10 energy))))
            (recur (inc k)
                   (+ energy (* (aget filters (+ (* m bins) k))
                                (aget power k))))))))
    (let [peak (reduce max -10.0 (seq mel))
          floor (- peak 8.0)]
      (dotimes [i (alength mel)]
        (aset-float mel i (float (/ (+ (max floor (aget mel i)) 4.0) 4.0))))
      mel)))

;; --- tokenizer + decoder ----------------------------------------------------

(defn load-tokenizer [path]
  ;; Token strings are data, not map keys. Keywordizing JSON would turn ordinary
  ;; BPE pieces into Keywords and break byte decoding (and leak interned symbols).
  (let [raw (json/parse-string (slurp (io/file path)))
        vocab (get-in raw ["model" "vocab"])
        added (into {} (map (juxt #(get % "content") #(get % "id")))
                    (get raw "added_tokens"))]
    {:token->id (merge vocab added)
     :id->token (into {} (map (fn [[token id]] [(long id) token]))
                     (merge vocab added))
     :special added}))

(def ^:private byte-decoder
  (delay
    (let [visible (vec (concat (range 33 127) (range 161 173) (range 174 256)))
          missing (remove (set visible) (range 256))
          codes (concat visible (map-indexed (fn [index _] (+ 256 index)) missing))
          bytes (concat visible missing)]
      (zipmap (map char codes) bytes))))

(defn detokenize [{:keys [id->token special]} token-ids]
  (let [special? (set (keys special))
        encoded (apply str (remove special? (keep id->token token-ids)))
        out (ByteArrayOutputStream.)]
    (doseq [ch encoded]
      (when-let [b (get @byte-decoder ch)]
        (.write out (int b))))
    (str/trim (String. (.toByteArray out) StandardCharsets/UTF_8))))

(defn- timestamp-seconds [token]
  (some->> token
           (re-matches #"<\|(\d+(?:\.\d+)?)\|>")
           second
           parse-double))

(defn timestamp-segments [tokenizer token-ids duration-s]
  (let [special-ids (set (vals (:special tokenizer)))]
    (loop [[token-id & more] token-ids, start nil, text-ids [], segments []]
      (if token-id
        (if-let [timestamp (timestamp-seconds (get-in tokenizer [:id->token token-id]))]
          (if (nil? start)
            (recur more timestamp [] segments)
            (let [text (detokenize tokenizer text-ids)
                  segments (cond-> segments
                             (seq text) (conj {:start start :end timestamp :text text}))]
              (recur more timestamp [] segments)))
          (recur more start
                 (cond-> text-ids (not (special-ids token-id)) (conj token-id))
                 segments))
        (let [text (detokenize tokenizer text-ids)]
          (cond-> segments
            (seq text) (conj {:start (or start 0.0)
                              :end duration-s
                              :text text})))))))

(defn- required-name [names candidates kind]
  (or (some (set names) candidates)
      (throw (ex-info (str "unsupported ONNX " (name kind) " graph")
                      {:available names :expected candidates}))))

(defn- argmax-last [^floats logits shape]
  (let [vocab (long (last shape))
        offset (- (alength logits) vocab)]
    (loop [index 1, best 0, best-value (aget logits offset)]
      (if (= index vocab)
        best
        (let [value (aget logits (+ offset index))]
          (if (> value best-value)
            (recur (inc index) index value)
            (recur (inc index) best best-value)))))))

(defn- decoder-inputs [names ids encoder]
  (let [id-name (required-name names ["input_ids" "decoder_input_ids"] :decoder-input)
        hidden-name (required-name names ["encoder_hidden_states" "encoder_outputs"]
                                   :encoder-state)
        basic #{id-name hidden-name}
        extra (remove basic names)]
    (when (seq extra)
      (throw (ex-info "decoder graph requires unsupported cache/mask inputs"
                      {:unsupported (vec extra)})))
    [[id-name (long-tensor ids [1 (count ids)])]
     [hidden-name (float-tensor (:data encoder) (:shape encoder))]]))

(defn decode-loop [decoder encoder tokenizer language opts]
  (let [{:keys [inputs outputs]} (io-names decoder)
        logits-name (required-name outputs ["logits" "output"] :decoder-output)
        special (:special tokenizer)
        language-token (str "<|" (-> (or language "en") str/lower-case
                                      (str/split #"-") first) "|>")
        prompt (->> [(get special "<|startoftranscript|>")
                     (get special language-token)
                     (get special "<|transcribe|>")]
                    (remove nil?) vec)
        eot (get special "<|endoftext|>")
        max-new (long (or (:max-new-tokens opts) 448))]
    (when (empty? prompt)
      (throw (ex-info "tokenizer lacks Whisper start tokens"
                      {:language-token language-token})))
    (loop [ids prompt, generated [], remaining max-new]
      (if (zero? remaining)
        generated
        (let [logits (first (run-graph decoder
                                       (decoder-inputs inputs ids encoder)
                                       [logits-name]))
              token (long (argmax-last (:data logits) (:shape logits)))]
          (if (= token eot)
            generated
            (recur (conj ids token) (conj generated token) (dec remaining))))))))

(defn- model-path [model-dir opts key default-name]
  (str (io/file model-dir (or (get opts key) default-name))))

(defn- sample-chunks [^floats samples]
  (let [chunk-size (* (:sample-rate whisper-mel-spec) 30)
        length (alength samples)]
    (mapv (fn [offset]
            (let [size (min chunk-size (- length offset))
                  chunk (float-array size)]
              (System/arraycopy samples offset chunk 0 size)
              {:offset offset :samples chunk}))
          (range 0 (max 1 length) chunk-size))))

(defn- transcribe-chunk [model-dir tokenizer language opts {:keys [offset samples]}]
  (let [encoder (session (model-path model-dir opts :encoder-file "encoder.onnx"))
        decoder (session (model-path model-dir opts :decoder-file "decoder.onnx"))
        encoder-io (io-names encoder)
        input-name (required-name (:inputs encoder-io) ["input_features" "mel"] :encoder-input)
        output-name (required-name (:outputs encoder-io)
                                   ["last_hidden_state" "output"] :encoder-output)
        mel (log-mel-spectrogram samples)
        encoded (first (run-graph encoder
                                  [[input-name (float-tensor mel [1 80 3000])]]
                                  [output-name]))
        tokens (decode-loop decoder encoded tokenizer language opts)
        duration (/ (alength ^floats samples) 16000.0)
        offset-s (/ offset 16000.0)]
    (mapv #(-> % (update :start + offset-s) (update :end + offset-s))
          (timestamp-segments tokenizer tokens duration))))

;; --- the pipeline entrypoint ------------------------------------------------

(defn transcribe-wav
  "Entrypoint the OUTER record delegates to. `model-dir` holds encoder.onnx +
   decoder.onnx + tokenizer.json; `path` is the 16 kHz mono PCM WAV; `language` a
   BCP-47 tag; `opts` the merged config/call opts. Returns the ITranscriber Result.

   The implementation reads PCM, builds Whisper's log-mel tensor, runs the encoder,
   greedily steps a cacheless decoder graph, decodes byte-level BPE, projects
   timestamp tokens, and funnels every segment through the shared LSP guardrail."
  [model-dir path language opts]
  (let [wav (sup/read-wav-mono-floats path)]
    (if (r/err? wav)
      wav
      (try
        (let [{:keys [samples sample-rate]} (:ok wav)]
          (when (not= 16000 sample-rate)
            (throw (ex-info "onnx-bytedeco requires 16 kHz PCM"
                            {:sample-rate sample-rate})))
          (let [tokenizer (load-tokenizer
                           (model-path model-dir opts :tokenizer-file "tokenizer.json"))
                raw (mapcat #(transcribe-chunk model-dir tokenizer language opts %)
                            (sample-chunks samples))]
            (r/ok {:segments (sup/normalize-segments raw {:unit :s})})))
        (catch Throwable throwable
          (r/err :error/asr-failed
                 {:reason (or (ex-message throwable) "onnx-bytedeco failed")
                  :model-dir model-dir}))))))
