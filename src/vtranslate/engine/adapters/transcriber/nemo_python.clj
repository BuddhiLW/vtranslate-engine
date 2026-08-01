(ns vtranslate.engine.adapters.transcriber.nemo-python
  "ITranscriber over NVIDIA NeMo (Canary / Parakeet) through libpython-clj —
   provider keys :canary and :parakeet. Canary is a multilingual ASR *and* AST
   model: given a target language different from the source it emits a
   TRANSLATION rather than a transcription, which is why this adapter is the one
   place in the family where `:target-language` reaches an ITranscriber. Parakeet
   is English ASR only and ignores every language hint.

   This is the CORE-SAFE OUTER half of the mandatory core/native SPLIT (mirrors
   whisper-jni -> whisper-jni-native, qwen3-asr -> qwen3-asr-native): it MUST
   load on the engine core classpath with the optional :nemo alias ABSENT,
   because main.clj/register-adapters! best-effort (require)s it unconditionally.
   So it (require)s NO libpython-clj ns at load — it only PROBES for the jar via
   io/resource and, once the probe passes, lazy-(requiring-resolve)s the sibling
   *native* ns that embeds the interpreter.

   Capability gate at RESOLVE time (not call time) so the router fallback chain
   cleanly SKIPS an unavailable backend: available iff (a) the libpython-clj jar
   is on the classpath, (b) :model-name names a known NeMo model, and (c) the
   configured Python lives at an existing executable. Every decode failure is
   LOUD (:error/asr-failed) — never a fake or empty transcript.

   Per-span language routing: spans arrive via (:spans opts), each optionally
   carrying :language (a BCP-47 tag, preserved on segments by calc.transcription).
   Canary takes source_lang / target_lang per call, so each span is sliced (same
   geometry as whisper-jni) and decoded with its OWN source language — the span's
   :language wins over the port-level `language`. NeMo returns plain text per
   utterance, so each span yields ONE segment at the span's bounds. Raw
   hypotheses funnel through support/normalize-segments — the single Liskov
   guardrail, never hand-rolled."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [hive-dsl.result :as r]
            [vtranslate.engine.port.transcriber :as p.asr]
            [vtranslate.engine.adapters.transcriber.support :as sup]
            [vtranslate.engine.providers.transcriber-registry :as reg]
            [vtranslate.engine.providers.compatibility :as compat])
  (:import [java.io File]))

;; The interop lives ONE ns over so its top-level (require [libpython-clj2.python])
;; is never touched unless the backend jar is present — requiring an absent ns
;; throws at load, which would break the core.
(def ^:private native-transcribe-sym
  'vtranslate.engine.adapters.transcriber.nemo-python-native/transcribe-file)

(def model-names
  "Provider key -> the NeMo pretrained model it serves, and whether that model
   can translate (AST) as well as transcribe."
  {:canary   {:model "nvidia/canary-1b-flash"      :translates? true  :multilingual? true}
   :parakeet {:model "nvidia/parakeet-tdt-0.6b-v2" :translates? false :multilingual? false}})

(def default-python
  "Fallback interpreter when config names none. A conda env is the expected
   shape — NeMo needs Python <= 3.12, which is rarely the system one."
  (str (System/getProperty "user.home") "/anaconda3/envs/vtranslate-asr/bin/python"))

(defn backend-present?
  "Resource probe — true only when the :nemo deps alias put libpython-clj on the
   classpath. Deliberately NOT a (require ...): the outer ns must stay loadable
   when the optional dep is absent."
  []
  (boolean (io/resource "libpython_clj2/python.clj")))

;; NeMo wants ISO-639-1 (Canary flash: en/de/es/fr and more per model card),
;; never a full BCP-47 tag. Unmapped/absent => nil, which lets NeMo default.
(defn ->lang-code
  "Primary subtag of a BCP-47 tag, lower-cased. nil/blank => nil."
  [language]
  (some-> language str str/trim not-empty
          (str/split #"-") first str/lower-case not-empty))

(defn derive-library-path
  "The libpython3.X.so belonging to `python-executable`'s environment, i.e.
   <env>/lib/libpython3.X.so for <env>/bin/python. Passing the executable ALONE
   is not enough: libpython-clj then embeds whatever libpython it finds first,
   and a mismatched one brings its own sys.path — which shows up as
   `ModuleNotFoundError: No module named 'nemo'` against an env that plainly has
   it. => path string | nil (nil lets libpython-clj probe for itself)."
  [python-executable]
  (when python-executable
    (let [env-root (some-> (File. ^String python-executable) .getParentFile .getParentFile)
          lib      (when env-root (File. ^File env-root "lib"))]
      (when (and lib (.isDirectory lib))
        ;; ordered by MINOR VERSION, not by name — "3.9" sorts after "3.12"
        ;; lexicographically, which would pick the older interpreter
        (let [candidates (->> (.listFiles lib)
                              (keep (fn [^File f]
                                      (when-let [[_ minor] (re-matches
                                                            #"libpython3\.(\d+)\.so"
                                                            (.getName f))]
                                        [(parse-long minor) f])))
                              (sort-by first))]
          (when-let [[_ ^File chosen] (last candidates)]
            (.getPath chosen)))))))

(defn- sample-range [sample-rate total-samples pad-ms {:keys [start-ms end-ms]}]
  (let [start-ms (max 0 (- (or start-ms 0) pad-ms))
        end-ms   (+ (or end-ms 0) pad-ms)
        start    (long (Math/floor (* sample-rate (/ (double start-ms) 1000.0))))
        end      (long (Math/ceil (* sample-rate (/ (double end-ms) 1000.0))))
        start    (max 0 (min total-samples start))
        end      (max start (min total-samples end))]
    [start end]))

(defn- slice-samples [samples start end]
  (let [n   (max 0 (- end start))
        out (float-array n)]
    (when (pos? n)
      (System/arraycopy ^floats samples start out 0 n))
    out))

(defn- samples->ms [sample sample-rate]
  (long (Math/round (* 1000.0 (/ (double sample) sample-rate)))))

(defn- span->segment [text {:keys [start-ms end-ms language]}]
  (cond-> {:start-ms (or start-ms 0) :end-ms (or end-ms 0) :text text}
    language (assoc :language language)))

(defn- temp-wav! []
  (doto (File/createTempFile "vtranslate-nemo-" ".wav") .deleteOnExit))

(defn- decode-slice
  "Write `samples` to a scratch WAV and hand the PATH to `transcribe-fn` — NeMo's
   entry point takes files, not buffers. The scratch file is always removed."
  [transcribe-fn model samples sample-rate source-lang target-lang]
  (let [wav (temp-wav!)]
    (try
      (r/let-ok [path (sup/write-wav-mono! (.getPath wav) samples sample-rate)]
        (transcribe-fn model path source-lang target-lang))
      (finally (.delete wav)))))

(defn transcribe-with-spans
  "Route `spans` through `transcribe-fn` [model wav-path source-lang target-lang
   => (r/ok text) | (r/err ...)]: slice the samples per span and decode each with
   the span's OWN source language (falling back to the port-level `language`).
   `target-lang` is passed unchanged on every call — for Canary that selects AST
   when it differs from the source. => (r/ok [raw-segments]), one per span. No
   spans => ONE whole-clip call."
  [transcribe-fn model samples sample-rate language target-language spans span-pad-ms]
  (let [target (->lang-code target-language)]
    (if (seq spans)
      (reduce
       (fn [acc-res span]
         (r/let-ok [acc acc-res]
           (let [[start end] (sample-range sample-rate (alength ^floats samples)
                                           span-pad-ms span)]
             (if (= start end)
               (r/ok acc)
               (r/let-ok [text (decode-slice transcribe-fn model
                                             (slice-samples samples start end)
                                             sample-rate
                                             (->lang-code (or (:language span) language))
                                             target)]
                 (r/ok (conj acc (span->segment text span))))))))
       (r/ok [])
       spans)
      (r/let-ok [text (decode-slice transcribe-fn model samples sample-rate
                                    (->lang-code language) target)]
        (r/ok [{:start-ms 0
                :end-ms   (samples->ms (alength ^floats samples) sample-rate)
                :text     text}])))))

(defrecord NemoTranscriber [model target-language span-pad-ms python-executable library-path device multilingual?]
  p.asr/ITranscriber
  (transcribe [_ audio-source language opts]
    (if-let [path (sup/audio->path audio-source)]
      (r/let-ok [{:keys [samples sample-rate]} (sup/read-wav-mono-floats path)
                 raw (r/guard Throwable
                              (r/err :error/asr-failed
                                     {:reason "nemo python backend failed to load"})
                       (let [native (requiring-resolve native-transcribe-sym)
                             ;; the interpreter + device are adapter state, not
                             ;; per-span data — bound here so the span loop keeps
                             ;; the narrow [model path src tgt] contract
                             ;; an English-only RNNT (Parakeet) raises TypeError
                             ;; on source_lang/target_lang — the hints are dropped
                             ;; here rather than silently mis-sent
                             transcribe-fn (fn [model path source-lang target-lang]
                                             (native model path
                                                     (when multilingual? source-lang)
                                                     (when multilingual? target-lang)
                                                     {:python-executable python-executable
                                                      :library-path library-path
                                                      :device device}))]
                         (transcribe-with-spans transcribe-fn model samples sample-rate
                                                language
                                                (or (:target-language opts) target-language)
                                                (:spans opts) span-pad-ms)))]
        (r/ok {:segments (sup/normalize-segments raw {:unit :ms})}))
      (r/err :error/asr-failed {:reason "audio-source carries no path"}))))

;; --- provider registry (OCP self-registration) ------------------------------

(defn resolve-nemo
  "Gate and build the NeMo transcriber for `provider-key`."
  [provider-key config]
  (let [opts        (get config :transcriber-opts)
        {:keys [model translates? multilingual?]} (get model-names provider-key)
        model       (or (:model-name opts) model)
        python      (or (:python-executable opts) default-python)
        target      (:target-language opts)
        ;; A shared workstation GPU is routinely already full (an ollama server
        ;; alone can hold 5 GB of an 8 GB card), and NeMo raises CUDA OOM at LOAD
        ;; time. "cpu" is the safe default; set :device "cuda" to opt in.
        device      (or (:device opts) "cpu")
        library-path (or (:library-path opts) (derive-library-path python))
        span-pad-ms (long (get opts :span-pad-ms 500))]
    (cond
      (not (backend-present?))
      (r/err :error/transcriber-unavailable
             {:provider provider-key
              :hint     "add the :nemo deps alias (clj-python/libpython-clj) so libpython-clj2.python is on the classpath"})

      (nil? model)
      (r/err :error/transcriber-unavailable
             {:provider provider-key
              :hint     (str "unknown NeMo provider — known: " (pr-str (set (keys model-names))))})

      (not (.canExecute (File. ^String python)))
      (r/err :error/transcriber-unavailable
             {:provider provider-key
              :python   python
              :hint     (str "no executable Python at " python
                             " — NeMo needs Python <= 3.12; set config "
                             "[:transcriber-opts :python-executable]")})

      (and target (not translates?))
      (r/err :error/transcriber-unavailable
             {:provider provider-key
              :model    model
              :hint     (str model " transcribes only; drop [:transcriber-opts "
                             ":target-language] or select :canary for AST")})

      :else
      (r/ok (->NemoTranscriber model target span-pad-ms python library-path device multilingual?)))))

(defmethod reg/resolve-transcriber :canary   [k config] (resolve-nemo k config))
(defmethod reg/resolve-transcriber :parakeet [k config] (resolve-nemo k config))

(defmethod compat/segmentation-required :canary   [_] :utterance)

(defmethod compat/segmentation-required :parakeet [_] :utterance)
