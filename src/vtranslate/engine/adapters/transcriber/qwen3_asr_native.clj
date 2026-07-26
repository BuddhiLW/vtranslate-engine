(ns vtranslate.engine.adapters.transcriber.qwen3-asr-native
  "NATIVE half of the pretrained-rstr adapter — the ONLY ns that (require)s
   pretrained.asr, so it is loadable ONLY when the :qwen3-asr alias is on the
   classpath. The core-safe outer ns (adapters.transcriber.qwen3-asr)
   lazy-(requiring-resolve)s this via its io/resource probe — never on the bare
   core cp (mirrors whisper-jni -> whisper-jni-native).

   HAZARDS encoded below:
   - Model weights are large (Qwen3-ASR-0.6B ~1-2 GB; first load also quantizes
     to the Q8 stream cache once); re-init per call is untenable, so a loaded
     model is CACHED per (model-key, model-dir) for the JVM's life (a resident
     model-server, never freed — the process owns its lifetime).
   - A nil :model-dir means pretrained.hub auto-downloads sha-pinned weights
     from HF into ~/.cache/raster/models on first use; a download/IO failure
     throws and surfaces as :error/asr-failed — loud, never a fake transcript."
  (:require [hive-dsl.result :as r]
            [pretrained.asr :as asr]))

(def ^:private model-cache
  "[model-key model-dir] -> loaded pretrained.asr model. Guards the multi-GB
   init cost; entries are intentionally never evicted/freed (resident weights
   for the JVM's lifetime)."
  (atom {}))

(defn- model-for
  "Get-or-create the cached pretrained.asr model for `model-key` (+ optional
   local weights `model-dir`). Double-checked under `locking` so a race can't
   init the same weights twice."
  [model-key model-dir]
  (let [k [model-key model-dir]]
    (or (get @model-cache k)
        (locking model-cache
          (or (get @model-cache k)
              (let [m (if model-dir
                        (asr/load-asr model-key model-dir)
                        (asr/load-asr model-key))]
                (swap! model-cache assoc k m)
                m))))))

(defn transcribe-samples
  "Run Qwen3-ASR over `samples` (16 kHz mono float[]) via the cached model,
   with `language-name` (\"English\", ...) as the decoder language hint (nil =
   auto-detect). => (r/ok text) | (r/err :error/asr-failed {...}). Fails loud:
   any load/decode throw becomes :error/asr-failed, never a fake transcript."
  [model-key model-dir ^floats samples language-name]
  (r/try-effect* :error/asr-failed
    (when (nil? samples)
      (throw (ex-info "no audio samples (nil) — upstream audio decode produced nothing"
                      {:model-key model-key})))
    (let [m    (model-for model-key model-dir)
          opts (cond-> {} language-name (assoc :language language-name))]
      (asr/transcribe m {:samples samples :rate 16000} opts))))
