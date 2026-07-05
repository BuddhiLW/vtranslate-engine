(ns vtranslate.engine.adapters.transcriber.sherpa-onnx
  "ITranscriber over sherpa-onnx (k2-fsa) — offline Whisper (and any offline ASR
   sherpa serves) decoded through ONNX Runtime, IN-PROCESS via the JNI bindings.

   CORE-SAFE / NATIVE SPLIT (mirrors collect.port -> collect.ffmpeg): this OUTER
   ns MUST load on the core classpath with the :sherpa alias ABSENT, because
   main.clj/register-adapters! best-effort (require)s it unconditionally. So it
   carries NO (:import ...) of a com.k2fsa.sherpa.onnx type — every reference to a
   native class lives in the sibling `sherpa-onnx-native` ns, which this ns
   lazy-(requiring-resolve)s ONLY after the availability probe has passed. Loading
   the native ns imports the backend classes; doing so before the probe would
   throw at load on a core REPL — exactly the hazard the split removes.

   Capability gate at RESOLVE time (not call time): the backend is 'available' iff
   the OfflineRecognizer class is present AND the encoder/decoder/tokens model
   files exist under config [:transcriber-opts :model-dir]. Absent either => the
   defmethod returns (r/err :error/transcriber-unavailable ...) so the router's
   fallback chain SKIPS this provider cleanly — never a silent fake transcript.

   Fail-loud: any native load / decode failure surfaces as (r/err :error/asr-failed
   {:reason ...}); we never emit an empty or fabricated transcript. Raw hypotheses
   (full text + per-token strings + per-token timestamps in SECONDS) are mapped
   onto the port's segment contract by the PURE helpers here and funneled through
   support/normalize-segments {:unit :s} — the single Liskov guardrail shared by
   every adapter."
  (:require [clojure.string :as str]
            [hive-dsl.result :as r]
            [vtranslate.engine.port.transcriber :as p.asr]
            [vtranslate.engine.adapters.transcriber.support :as sup]
            [vtranslate.engine.providers.transcriber-registry :as reg])
  (:import [java.io File]))

;; --- model-file resolution (pure, core-JDK only) ----------------------------
;; A sherpa whisper bundle ships as <name>-encoder[.int8].onnx / <name>-decoder
;; [.int8].onnx / tokens.txt. We accept explicit config overrides (absolute, or
;; relative to :model-dir) and otherwise DISCOVER by the stable filename shape,
;; picking the first match in sorted order so the choice is deterministic.

(defn- existing-file
  "=> the path string iff it names an existing regular file, else nil."
  ^String [^String path]
  (when (and path (.isFile (File. path))) path))

(defn- resolve-in
  "Resolve `name` against `dir`: absolute names pass through, relative ones are
   joined under `dir`. => existing path string | nil."
  [^String dir ^String name]
  (existing-file
   (let [f (File. name)]
     (if (.isAbsolute f) name (.getPath (File. (File. dir) name))))))

(defn- discover
  "First (sorted, deterministic) regular file under `dir` whose name matches `re`,
   as a full path string, else nil."
  [^String dir re]
  (some->> (.listFiles (File. dir))
           (filter #(.isFile ^File %))
           (map #(.getName ^File %))
           sort
           (filter #(re-find re %))
           first
           (resolve-in dir)))

(defn resolve-model-files
  "Locate {:encoder :decoder :tokens} under `model-dir`. `opts` may pin any of
   :encoder/:decoder/:tokens explicitly (absolute or dir-relative); unpinned ones
   are discovered by filename shape. => the map iff ALL THREE resolve to existing
   files, else nil (the gate reads nil as 'model absent')."
  [model-dir opts]
  (when (and model-dir (.isDirectory (File. ^String model-dir)))
    (let [pick (fn [explicit re]
                 (or (some->> explicit (resolve-in model-dir))
                     (discover model-dir re)))
          enc  (pick (:encoder opts) #"(?i)encoder.*\.onnx$")
          dec  (pick (:decoder opts) #"(?i)decoder.*\.onnx$")
          tok  (pick (:tokens opts)  #"(?i)tokens.*\.txt$")]
      (when (and enc dec tok)
        {:encoder enc :decoder dec :tokens tok}))))

;; --- availability probe (Class/forName; NO :import of the native type) ------

(defn backend-present?
  "True iff the sherpa-onnx OfflineRecognizer class is loadable — i.e. the :sherpa
   alias is on the classpath (and, transitively, its JNI natives static-init). We
   probe by reflection so this ns imports NO native type and stays core-loadable."
  []
  (try (Class/forName "com.k2fsa.sherpa.onnx.OfflineRecognizer") true
       (catch Throwable _ false)))

;; --- raw hypotheses -> contract segments (pure) -----------------------------

(defn- word-start?
  "Sentencepiece/BPE word-boundary sentinel: a token that opens a new word begins
   with a space or U+2581 (▁). Used to regroup per-token hypotheses into words."
  [^String tok]
  (boolean (and (seq tok)
                (let [c (.charAt tok 0)] (or (= c \space) (= (int c) 0x2581))))))

(defn- group-words
  "Regroup equal-length `tokens`/`starts` (starts in SECONDS) into words on the
   word-boundary sentinel. => vector of {:start s :toks [tok ...]} in order."
  [tokens starts]
  (reduce (fn [acc [tok t]]
            (if (or (empty? acc) (word-start? tok))
              (conj acc {:start (double t) :toks [tok]})
              (conj (pop acc) (update (peek acc) :toks conj tok))))
          []
          (map vector tokens starts)))

(defn result->raw-segments
  "PURE: map a sherpa result — full :text, per-token :tokens (String[]) and
   per-token :timestamps (float[], SECONDS) — onto raw segment maps (in seconds)
   for support/normalize-segments {:unit :s}.

   Whisper models return an EMPTY timestamps array, so the common path is ONE
   segment spanning [0, clip-duration] carrying the whole text — the same
   text-only fallback the OpenAI-compatible sibling uses. Token-timestamped models
   (transducer/paraformer) are regrouped into WORD segments: each word starts at
   its first token's timestamp and ends at the next word's start (the last word
   ends at the clip duration). normalize-segments then orders/shears/trims — we
   never hand-roll the contract invariant."
  [{:keys [text tokens timestamps]} duration-ms]
  (let [dur-s (/ (double (or duration-ms 0)) 1000.0)
        tks   (vec tokens)
        ts    (vec timestamps)]
    (if (or (empty? ts) (not= (count ts) (count tks)))
      [{:start 0.0 :end dur-s :text text}]
      (let [words (group-words tks ts)
            n     (count words)]
        (map-indexed
         (fn [i {:keys [start toks]}]
           {:start start
            :end   (if (< (inc i) n) (:start (nth words (inc i))) dur-s)
            :text  (-> (apply str toks) (str/replace "▁" " ") str/trim)})
         words)))))

;; --- the adapter ------------------------------------------------------------
;; `cfg` is the fully-resolved native config the record was gated with: absolute
;; encoder/decoder/tokens paths plus decode knobs. The record holds no native
;; handle — the OfflineRecognizer is stateful and owns off-heap memory (JNI), so
;; the native ns builds ONE per call and release()s it in a finally (mirroring the
;; ffmpeg grabber's with-open discipline). At call time the port's `language`
;; (a BCP-47 source tag) wins over the configured default; we pass only its
;; primary subtag, the language code whisper's decoder expects.

(defn- primary-subtag [tag]
  (some-> tag str str/trim not-empty (str/split #"-") first str/lower-case not-empty))

(defrecord SherpaOnnxTranscriber [cfg]
  p.asr/ITranscriber
  (transcribe [_ audio-source language _opts]
    (if-let [path (sup/audio->path audio-source)]
      (r/let-ok [wav (sup/read-wav-mono-floats path)
                 raw (r/try-effect* :error/asr-failed
                       ;; Lazy-load the native ns ONLY here, past the resolve-time
                       ;; gate — importing it is what needs the :sherpa classes.
                       (let [run (requiring-resolve
                                  'vtranslate.engine.adapters.transcriber.sherpa-onnx-native/transcribe-raw)]
                         (run (assoc cfg :language (or (primary-subtag language)
                                                       (:language cfg)))
                              (:samples wav)
                              (int (:sample-rate wav)))))]
        (r/ok {:segments (sup/normalize-segments
                          (result->raw-segments raw (sup/wav-duration-ms path))
                          {:unit :s})}))
      (r/err :error/asr-failed {:reason "audio-source carries no path"}))))

;; --- provider registry (OCP self-registration) ------------------------------

(defn- build-cfg
  "Assemble the native config from resolved model files + decode knobs read from
   config [:transcriber-opts]. Defaults mirror sherpa's own (greedy_search, cpu,
   1 thread, whisper 'transcribe'); :language is left blank so the port's per-call
   source tag drives it (blank => whisper language auto-detect)."
  [model-files opts]
  (merge model-files
         {:num-threads     (int (get opts :num-threads 1))
          :language        (or (some-> (:language opts) str str/trim not-empty) "")
          :task            (get opts :task "transcribe")
          :provider        (get opts :provider "cpu")
          :decoding-method (get opts :decoding-method "greedy_search")}))

(defn resolve-provider
  "Capability-gated resolve for :sherpa-onnx. Available iff the native class is
   present AND the model bundle exists under [:transcriber-opts :model-dir]. Else
   (r/err :error/transcriber-unavailable ...) so router fallback skips it."
  [config]
  (let [opts       (get config :transcriber-opts)
        model-dir  (get opts :model-dir)
        model-files (resolve-model-files model-dir opts)]
    (cond
      (not (backend-present?))
      (r/err :error/transcriber-unavailable
             {:provider :sherpa-onnx
              :hint     "add the :sherpa deps alias (com.k2fsa.sherpa.onnx/sherpa-onnx) to the classpath"})

      (nil? model-files)
      (r/err :error/transcriber-unavailable
             {:provider :sherpa-onnx
              :model-dir model-dir
              :hint     "set config [:transcriber-opts :model-dir] to a dir holding encoder/decoder .onnx + tokens.txt"})

      :else
      (r/ok (->SherpaOnnxTranscriber (build-cfg model-files opts))))))

(defmethod reg/resolve-transcriber :sherpa-onnx
  [_ config]
  (resolve-provider config))
