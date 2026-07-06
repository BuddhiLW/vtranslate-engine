(ns vtranslate.engine.adapters.transcriber.whisper-jni
  "ITranscriber over whisper.cpp via JNI (io.github.givimad/whisper-jni) — the
   in-process local ASR adapter (provider key :whisper-local). This is the
   CORE-SAFE OUTER half of the mandatory core/native SPLIT (mirrors collect.port
   -> collect.ffmpeg): it MUST load on the engine core classpath with the optional
   :whisper-jni alias ABSENT, because main.clj/register-adapters! best-effort
   (require)s it unconditionally. Therefore it carries NO (:import ...) of any
   whisperjni type — it only PROBES for the backend with Class/forName and, once
   the probe passes, lazy-(require)s the sibling *native* ns that does the real
   interop. Keeping the import out of here is the whole reason the split exists:
   an (:import ...) of an absent class throws at ns-load, which would break the
   core.

   Capability gate at RESOLVE time (not call time) so the router fallback chain
   cleanly SKIPS an unavailable backend instead of exploding mid-transcribe: the
   backend is available only when (a) the WhisperJNI class is on the classpath AND
   (b) a ggml/gguf model file actually exists at the configured path; otherwise
   resolve returns (r/err :error/transcriber-unavailable ...). transcribe itself
   fails LOUD (:error/asr-failed) on any decode/native failure — never a fake or
   empty transcript. Raw whisper hypotheses are shaped to the port contract by
   support/normalize-segments (the single LSP guardrail every backend funnels
   through), NEVER by hand-rolled segment code here."
  (:require [hive-dsl.result :as r]
            [vtranslate.engine.port.transcriber :as p.asr]
            [vtranslate.engine.adapters.transcriber.support :as sup]
            [vtranslate.engine.providers.transcriber-registry :as reg]))

;; The native interop lives ONE ns over so its top-level (:import ...) of the
;; whisperjni types is never touched unless the backend is present. We reach it
;; by symbol + requiring-resolve so the core classpath never tries to load it.
(def ^:private native-transcribe-sym
  'vtranslate.engine.adapters.transcriber.whisper-jni-native/transcribe-samples)

(def default-model-path
  "Fallback ggml/gguf weights location when config gives none. Relative to the
   engine's working dir — activation = drop a model here or set :model-path."
  "models/ggml-large-v3.bin")

(defn- whisper-jni-present?
  "Class/forName probe — true only when the :whisper-jni deps alias put
   io.github.givimad.whisperjni.WhisperJNI on the classpath. Deliberately NOT an
   (:import ...): the whole outer ns must stay loadable when the optional native
   dep is absent (exactly how collect.port stays loadable without bytedeco)."
  []
  (try (Class/forName "io.github.givimad.whisperjni.WhisperJNI") true
       (catch Throwable _ false)))

(defn- model-path-for [config]
  (or (get-in config [:transcriber-opts :model-path]) default-model-path))

(defn- model-present? [model-path]
  (boolean (and model-path (.exists (java.io.File. ^String model-path)))))

(defn- sample-range [sample-rate total-samples pad-ms {:keys [start-ms end-ms]}]
  (let [start-ms (max 0 (- (or start-ms 0) pad-ms))
        end-ms   (+ (or end-ms 0) pad-ms)
        start    (long (Math/floor (* sample-rate (/ (double start-ms) 1000.0))))
        end      (long (Math/ceil (* sample-rate (/ (double end-ms) 1000.0))))
        start    (max 0 (min total-samples start))
        end      (max start (min total-samples end))]
    [start end]))

(defn- slice-samples [samples start end]
  (let [n (max 0 (- end start))
        out (float-array n)]
    (when (pos? n)
      (System/arraycopy ^floats samples start out 0 n))
    out))

(defn- offset-segments [offset-ms raw]
  (mapv (fn [seg]
          (cond-> seg
            (contains? seg :start-ms) (update :start-ms + offset-ms)
            (contains? seg :end-ms)   (update :end-ms + offset-ms)
            (contains? seg :start)    (update :start + offset-ms)
            (contains? seg :end)      (update :end + offset-ms)))
        raw))

(defn- samples->ms [sample sample-rate]
  (long (Math/round (* 1000.0 (/ (double sample) sample-rate)))))

(defn- transcribe-with-spans
  [transcribe-samples model-path use-gpu? samples sample-rate language spans span-pad-ms]
  (if (seq spans)
    (reduce
     (fn [acc-res span]
       (r/let-ok [acc acc-res]
         (let [[start end] (sample-range sample-rate (alength ^floats samples) span-pad-ms span)]
           (if (= start end)
             (r/ok acc)
             (r/let-ok [raw (transcribe-samples model-path use-gpu?
                                                (slice-samples samples start end)
                                                language)]
               (r/ok (into acc (offset-segments (samples->ms start sample-rate) raw))))))))
     (r/ok [])
     spans)
    (transcribe-samples model-path use-gpu? samples language)))

(defrecord WhisperLocalTranscriber [model-path use-gpu? span-pad-ms]
  p.asr/ITranscriber
  (transcribe [_ audio-source language opts]
    (if-let [path (sup/audio->path audio-source)]
      (r/let-ok [{:keys [samples sample-rate]} (sup/read-wav-mono-floats path)
                 raw (r/guard Throwable
                              (r/err :error/asr-failed
                                     {:reason "whisper-jni native backend failed to load"})
                       (let [transcribe-samples @(requiring-resolve native-transcribe-sym)]
                         (transcribe-with-spans transcribe-samples model-path use-gpu?
                                                samples sample-rate language (:spans opts)
                                                span-pad-ms)))]
        (r/ok {:segments (sup/normalize-segments raw {:unit :ms})}))
      (r/err :error/asr-failed {:reason "audio-source carries no path"}))))

;; --- provider registry (OCP self-registration) ------------------------------

(defmethod reg/resolve-transcriber :whisper-local
  [_ config]
  (let [model-path  (model-path-for config)
        use-gpu?    (boolean (get-in config [:transcriber-opts :use-gpu?] false))
        span-pad-ms (long (get-in config [:transcriber-opts :span-pad-ms] 500))]
    (cond
      ;; Two distinct unavailability causes, each with its own actionable hint,
      ;; so an operator sees WHICH half is missing (the jar or the weights).
      (not (whisper-jni-present?))
      (r/err :error/transcriber-unavailable
             {:provider :whisper-local
              :hint     "add the :whisper-jni deps alias (io.github.givimad/whisper-jni) so WhisperJNI is on the classpath"})

      (not (model-present? model-path))
      (r/err :error/transcriber-unavailable
             {:provider   :whisper-local
              :model-path model-path
              :hint       (str "no ggml/gguf model at " model-path
                               " — download one or set config [:transcriber-opts :model-path]")})

      :else
      (r/ok (->WhisperLocalTranscriber model-path use-gpu? span-pad-ms)))))