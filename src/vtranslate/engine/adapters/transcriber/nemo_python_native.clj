(ns vtranslate.engine.adapters.transcriber.nemo-python-native
  "NATIVE half of the NeMo adapter — the ONLY ns that (require)s libpython-clj,
   so it is loadable ONLY when the :nemo alias is on the classpath. The core-safe
   outer ns (adapters.transcriber.nemo-python) lazy-(requiring-resolve)s this
   past its io/resource probe, never on the bare core cp.

   HAZARDS encoded below:
   - The embedded interpreter is process-global and can be initialized ONCE. It
     is therefore started behind a delay keyed to nothing: a second config asking
     for a different interpreter CANNOT be honoured, so it fails loud rather than
     silently decoding against the wrong Python.
   - NeMo checkpoints are large (canary-1b ~4 GB) and load onto the GPU; re-init
     per call is untenable, so a loaded model is CACHED per model name for the
     process's life — resident weights, intentionally never freed.
   - Every CPython call is made under the GIL. The worker pool runs jobs
     concurrently, so omitting this segfaults the JVM rather than raising.
   - NeMo returns Hypothesis objects on recent versions and bare strings on
     older ones; both shapes are accepted."
  (:require [clojure.string :as str]
            [hive-dsl.result :as r]
            [libpython-clj2.python :as py]))

(defonce ^:private interpreter (atom nil))

(defn- start-interpreter!
  "Initialize the process-global CPython once, against `python-executable`.
   A later, different interpreter request throws — it cannot be satisfied."
  [python-executable library-path]
  (let [started @interpreter]
    (cond
      (nil? started)
      (locking interpreter
        (or @interpreter
            (do (apply py/initialize!
                       (cond-> [:python-executable python-executable]
                         library-path (conj :library-path library-path)))
                (reset! interpreter python-executable))))

      (= started python-executable) started

      :else
      (throw (ex-info "the embedded Python is already running a different interpreter"
                      {:running started :requested python-executable})))))

(def ^:private model-cache
  "model-name -> loaded NeMo ASRModel. Guards the multi-GB load; entries are
   intentionally never evicted (resident weights for the process's lifetime)."
  (atom {}))

(defn- model-for
  "Get-or-create the cached NeMo model. Double-checked under `locking` so a race
   cannot load the same multi-GB checkpoint twice."
  [model-name]
  (or (get @model-cache model-name)
      (locking model-cache
        (or (get @model-cache model-name)
            (let [models (py/import-module "nemo.collections.asr.models")
                  loaded (py/py. (py/get-attr models "ASRModel")
                                 from_pretrained model_name model-name)]
              (swap! model-cache assoc model-name loaded)
              loaded)))))

(defn- hypothesis->text
  "NeMo yields Hypothesis objects (recent) or bare strings (older)."
  [hypothesis]
  (let [value (if (string? hypothesis)
                hypothesis
                (or (py/get-attr hypothesis "text") hypothesis))]
    (str/trim (str value))))

(defn transcribe-file
  "Decode the WAV at `wav-path` with the cached `model-name`. `source-lang` and
   `target-lang` are ISO-639-1 codes or nil; when both are present and differ,
   Canary emits a TRANSLATION. Models that take no language kwargs (Parakeet)
   are called without them.
   => (r/ok text) | (r/err :error/asr-failed {...}). Fails loud: any load/decode
   throw becomes :error/asr-failed, never a fake transcript."
  ([model-name wav-path source-lang target-lang]
   (transcribe-file model-name wav-path source-lang target-lang {}))
  ([model-name wav-path source-lang target-lang
    {:keys [python-executable library-path]}]
   (r/try-effect* :error/asr-failed
     (start-interpreter! (or python-executable
                             (System/getProperty "vtranslate.nemo.python")
                             "python3")
                         library-path)
     (py/with-gil
       (let [model  (model-for model-name)
             kwargs (cond-> {}
                      source-lang (assoc "source_lang" source-lang)
                      ;; Canary needs BOTH to select a task; with only a source
                      ;; it transcribes, which is the intended default.
                      (and source-lang target-lang) (assoc "target_lang" target-lang))
             result (if (seq kwargs)
                      (py/call-attr-kw model "transcribe" [[wav-path]] kwargs)
                      (py/call-attr model "transcribe" [wav-path]))
             first-hypothesis (first (py/->jvm result))]
         (hypothesis->text first-hypothesis))))))
