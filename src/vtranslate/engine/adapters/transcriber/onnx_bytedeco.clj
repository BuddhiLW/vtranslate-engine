(ns vtranslate.engine.adapters.transcriber.onnx-bytedeco
  "ITranscriber over RAW ONNX Runtime via bytedeco (provider :onnx-bytedeco) — the
   HEAVIEST backend: no framework hides the model, so a full Whisper decode is
   log-mel -> encoder Session.Run -> autoregressive decoder loop -> BPE detokenize,
   all hand-wired over org.bytedeco.onnxruntime tensors. This OUTER ns owns only the
   record, the resolve-time capability gate, and the core->native boundary; the real
   interop lives next door in onnx_bytedeco_native.clj.

   CORE-SAFE / NATIVE SPLIT (mirrors collect.port -> collect.ffmpeg):
   This ns MUST load on the engine CORE classpath with the :onnx alias ABSENT —
   main.clj/register-adapters! best-effort (require)s it unconditionally so its
   provider defmethod registers. Therefore it carries NO top-level (:import ...) of
   an onnxruntime class (that would throw at load without the dep). It PROBES the
   backend with Class/forName (no init) and only crosses into the native ns —
   which DOES (:import ...) the bytedeco classes — via requiring-resolve, lazily,
   AFTER the gate proved the classes are present. A core-classpath require of this
   ns thus touches zero bytedeco bytes.

   Capability gate at RESOLVE time (not call time): class present AND a model dir
   configured, else (r/err :error/transcriber-unavailable ...) so the router's
   fallback chain skips this backend cleanly instead of exploding mid-transcode.
   FAIL LOUD, NEVER FAKE: with the class+model present but the decode pipeline not
   yet built, transcribe returns (r/err :error/asr-failed ...) — a contract-valid
   failure, never an empty or fabricated transcript."
  (:require [clojure.java.io :as io]
            [hive-dsl.result :as r]
            [vtranslate.engine.port.transcriber :as p.asr]
            [vtranslate.engine.adapters.transcriber.support :as sup]
            [vtranslate.engine.providers.transcriber-registry :as reg]))

;; --- capability probe (core-safe: stands in for a top-level :import) ---------

(def ^:private onnx-probe-class
  "The class whose presence proves the :onnx deps alias is on the classpath. It is
   the C++ Ort::Session wrapper the native ns builds on; if it loads, so do the rest
   of org.bytedeco.onnxruntime.*."
  "org.bytedeco.onnxruntime.Session")

(defn- class-present?
  "True iff `cn` is resolvable on the current classloader. Class/forName WITHOUT
   initialization (initialize=false) so we never run a native <clinit> — and never
   dlopen the onnxruntime shared library — merely to answer 'is the dep here?'.
   Any Throwable (ClassNotFound, UnsatisfiedLinkError, NoClassDefFound) => false:
   a broken/half-present native install is 'unavailable', not a crash."
  [^String cn]
  (try
    (Class/forName cn false (.getContextClassLoader (Thread/currentThread)))
    true
    (catch Throwable _ false)))

(defn backend-available?
  "The core->native gate condition: are the bytedeco onnxruntime classes loadable?"
  []
  (class-present? onnx-probe-class))

;; --- model layout -----------------------------------------------------------

(def default-model-files
  "Filenames the native pipeline expects under [:transcriber-opts :model-dir].
   A Whisper ONNX export splits the graph in two (encoder consumes the mel, decoder
   is stepped autoregressively) plus the BPE tokenizer/vocab. Overridable per-key
   via [:transcriber-opts]. Kept HERE (core-safe, no dep) so the gate + hints can
   name them without loading the native ns."
  {:encoder "encoder.onnx" :decoder "decoder.onnx" :tokenizer "tokenizer.json"})

(defn- model-dir
  "The configured model directory, or nil. Read from [:transcriber-opts :model-dir]
   exactly as openai-compatible reads its per-provider opts."
  [config]
  (some-> (get-in config [:transcriber-opts :model-dir]) str not-empty))

;; --- the adapter ------------------------------------------------------------

(defrecord OnnxBytedecoTranscriber [model-dir opts]
  p.asr/ITranscriber
  (transcribe [_ audio-source language call-opts]
    (if-let [path (sup/audio->path audio-source)]
      ;; Cross the core->native boundary LAZILY. requiring-resolve loads the native
      ;; ns (which imports the bytedeco classes) only now — the gate already proved
      ;; those classes are present, so this is safe; if the alias vanished between
      ;; resolve and call, or the native ns fails to compile, the throw is caught
      ;; below and surfaced as a LOUD :error/asr-failed. We do NOT use try-effect*
      ;; here because the delegate ALREADY returns a Result: try-effect* would
      ;; double-wrap an ok in ok. So we catch throwables only and pass the Result
      ;; through untouched — the honest err (or, once built, the ok) is authored by
      ;; the native pipeline.
      (try
        (let [transcribe-wav (requiring-resolve
                              'vtranslate.engine.adapters.transcriber.onnx-bytedeco-native/transcribe-wav)]
          (transcribe-wav model-dir path language (merge opts call-opts)))
        (catch Throwable t
          (r/err :error/asr-failed
                 {:reason (str "onnx-bytedeco native pipeline error: " (ex-message t))})))
      (r/err :error/asr-failed {:reason "audio-source carries no path"}))))

;; --- provider registry (OCP self-registration) ------------------------------

(defn- resolve-onnx-bytedeco
  "Capability-gated resolve. Two independent capabilities must hold BEFORE we hand
   back a transcriber, so the router can skip us cleanly when either is missing:
     (1) the bytedeco onnxruntime classes are on the classpath (:onnx alias), and
     (2) a model dir is configured AND exists on disk (a configured-but-absent dir
         is a misconfiguration we surface now, not a runtime surprise mid-job).
   Either gap => :error/transcriber-unavailable with a targeted hint. Only when both
   hold do we build the record; the actual model FILES are opened lazily by the
   native pipeline on first transcribe."
  [config]
  (let [dir (model-dir config)]
    (cond
      (not (backend-available?))
      (r/err :error/transcriber-unavailable
             {:provider :onnx-bytedeco
              :hint     (str "add the :onnx deps alias (org.bytedeco/onnxruntime-platform) so "
                             onnx-probe-class " is on the classpath")})

      (not dir)
      (r/err :error/transcriber-unavailable
             {:provider :onnx-bytedeco
              :hint     (str "set config [:transcriber-opts :model-dir] to a dir holding "
                             (:encoder default-model-files) " + " (:decoder default-model-files)
                             " + " (:tokenizer default-model-files))})

      (not (.isDirectory (io/file dir)))
      (r/err :error/transcriber-unavailable
             {:provider :onnx-bytedeco
              :hint     (str "configured [:transcriber-opts :model-dir] " dir " is not a directory")})

      :else
      (r/ok (->OnnxBytedecoTranscriber dir (get config :transcriber-opts))))))

(defmethod reg/resolve-transcriber :onnx-bytedeco
  [_ config]
  (resolve-onnx-bytedeco config))
