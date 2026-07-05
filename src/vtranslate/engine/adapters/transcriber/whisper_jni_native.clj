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
     loaded WhisperContext is CACHED per (model-path, use-gpu?) for the JVM's life
     (a resident model-server, never freed — the process owns its lifetime).
   - whisper.cpp's default-state `full` is NOT reentrant on one context: two
     concurrent decodes on the same WhisperContext corrupt each other. We serialize
     `full` per context with `locking`, so parallelism across DIFFERENT models is
     free while same-context calls are ordered.
   - whisper segment timestamps are CENTISECONDS; ->ms is *10 here, and the raw
     {:start-ms :end-ms :text} maps go to support/normalize-segments upstream (no
     per-segment confidence -> the normalizer's 1.0 default applies)."
  (:require [clojure.string :as str]
            [hive-dsl.result :as r])
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
  "model-key -> loaded WhisperContext. Guards the multi-GB init cost; entries are
   intentionally never evicted/freed (resident weights for the JVM's lifetime)."
  (atom {}))

(defn- context-for
  "Get-or-create the cached WhisperContext for `model-path` (+ GPU flag). Double-
   checked under `locking` so a race can't init the same weights twice."
  ^WhisperContext [^WhisperJNI w model-path use-gpu?]
  (let [k [model-path (boolean use-gpu?)]]
    (or (get @ctx-cache k)
        (locking ctx-cache
          (or (get @ctx-cache k)
              (let [^WhisperContextParams params (WhisperContextParams.)
                    _   (set! (.-useGPU params) (boolean use-gpu?))
                    ctx (.init w (Path/of ^String model-path (into-array String []))
                               params)]
                (when (nil? ctx)
                  (throw (ex-info "whisper init returned no context (bad/corrupt model?)"
                                  {:model-path model-path})))
                (swap! ctx-cache assoc k ctx)
                ctx))))))

(defn- lang-code
  "Whisper wants an ISO-639-1 primary subtag (\"en\"), not a full BCP-47 tag
   (\"en-US\"); take the first subtag, lower-cased. nil/blank => nil (auto-detect)."
  [language]
  (some-> language str str/trim not-empty (str/split #"-") first str/lower-case not-empty))

(defn transcribe-samples
  "Run whisper.cpp over `samples` (16 kHz mono float[]) via the cached context for
   `model-path`, returning RAW hypotheses for support/normalize-segments to shape.
   => (r/ok [{:start-ms n :end-ms n :text s} ...]) | (r/err :error/asr-failed {...}).
   Fails loud: a non-zero `full` rc or any interop throw becomes :error/asr-failed,
   never a fake/empty transcript."
  [model-path use-gpu? ^floats samples language]
  (r/try-effect* :error/asr-failed
    (let [^WhisperJNI w   @jni
          ^WhisperContext ctx (context-for w model-path use-gpu?)
          ^WhisperFullParams params (WhisperFullParams.)
          lang (lang-code language)]
      (when lang (set! (.-language params) lang))
      ;; Surface results as DATA, not stdout noise — silence whisper's console.
      (set! (.-printProgress params) false)
      (set! (.-printRealtime params) false)
      (set! (.-printTimestamps params) false)
      ;; Serialize decode on THIS context (default-state full is non-reentrant).
      (locking ctx
        (let [rc (.full w ctx samples (alength samples))]
          (when-not (zero? rc)
            (throw (ex-info "whisper full() returned non-zero"
                            {:rc rc :model-path model-path})))
          (let [n (.fullNSegments w ctx)]
            (mapv (fn [i]
                    ;; centiseconds -> ms
                    {:start-ms (* 10 (.fullGetSegmentTimestamp0 w ctx i))
                     :end-ms   (* 10 (.fullGetSegmentTimestamp1 w ctx i))
                     :text     (.fullGetSegmentText w ctx i)})
                  (range n))))))))
