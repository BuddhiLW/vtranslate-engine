(ns vtranslate.engine.adapters.transcriber.qwen3-asr
  "ITranscriber over pretrained-rstr (org.replikativ/pretrained-rstr) — JVM-native
   Qwen3-ASR on the raster compiler (no Python, no ONNX runtime). Provider key
   :qwen3-asr. This is the CORE-SAFE OUTER half of the mandatory core/native
   SPLIT (mirrors whisper-jni -> whisper-jni-native): it MUST load on the engine
   core classpath with the optional :qwen3-asr alias ABSENT, because
   main.clj/register-adapters! best-effort (require)s it unconditionally. So it
   (require)s NO pretrained.* ns at load — it only PROBES for the backend jar via
   io/resource and, once the probe passes, lazy-(requiring-resolve)s the sibling
   *native* ns that does the real interop.

   Capability gate at RESOLVE time (not call time) so the router fallback chain
   cleanly SKIPS an unavailable backend: available iff (a) the pretrained-rstr
   jar is on the classpath, (b) :model-key names a known Qwen3-ASR registry key,
   and (c) a configured :model-dir (if any) is an existing directory. With no
   :model-dir the first transcribe auto-downloads sha-pinned weights from HF
   into ~/.cache/raster/models — a failed download surfaces LOUD as
   :error/asr-failed, never a fake transcript.

   Per-span language routing: silero-vad spans arrive via (:spans opts), each
   optionally carrying :language (a BCP-47 tag, preserved on segments by
   calc.transcription). Qwen3-ASR accepts a :language hint per call, so each
   span is sliced (sample-range/slice-samples, same geometry as whisper-jni) and
   transcribed with its OWN language hint — the span's :language wins over the
   port-level `language`. The hint wants a language NAME (\"English\"), not a
   BCP-47 tag, so ->language-name maps the primary subtag; unmapped/absent =>
   nil, the model's own auto-detect. Qwen3-ASR returns plain text (no word
   timestamps), so each span yields ONE segment at the span's bounds, carrying
   :language when the span tagged one. Raw hypotheses funnel through
   support/normalize-segments — the single Liskov guardrail, never hand-rolled."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [hive-dsl.result :as r]
            [vtranslate.engine.port.transcriber :as p.asr]
            [vtranslate.engine.adapters.transcriber.support :as sup]
            [vtranslate.engine.providers.transcriber-registry :as reg])
  (:import [java.io File]))

;; The native interop lives ONE ns over so its top-level (require [pretrained.asr])
;; is never touched unless the backend jar is present (the whole reason the split
;; exists: requiring an absent ns throws at load, which would break the core).
(def ^:private native-transcribe-sym
  'vtranslate.engine.adapters.transcriber.qwen3-asr-native/transcribe-samples)

(def default-model-key
  "Fallback pretrained-rstr registry key when config gives none."
  :qwen3-asr-0.6b)

(def known-model-keys
  "The multilingual Qwen3-ASR registry keys this provider serves."
  #{:qwen3-asr-0.6b :qwen3-asr-1.7b})

(defn backend-present?
  "Resource probe — true only when the :qwen3-asr deps alias put the
   pretrained-rstr jar on the classpath (it ships source, not AOT classes, so
   the probe is the .clj resource, not Class/forName). Deliberately NOT a
   (require ...): the whole outer ns must stay loadable when the optional dep
   is absent."
  []
  (boolean (io/resource "pretrained/asr.clj")))

;; Qwen3-ASR's :language hint is a language NAME (\"English\"), not a BCP-47 tag.
;; Unmapped subtags fall through to nil = the model's own auto-detect.
(def ^:private language-names
  {"en" "English" "zh" "Chinese" "yue" "Cantonese" "de" "German" "fr" "French"
   "es" "Spanish" "pt" "Portuguese" "it" "Italian" "nl" "Dutch" "ru" "Russian"
   "ja" "Japanese" "ko" "Korean" "ar" "Arabic" "hi" "Hindi" "tr" "Turkish"
   "vi" "Vietnamese" "th" "Thai" "id" "Indonesian" "pl" "Polish" "uk" "Ukrainian"
   "sv" "Swedish"})

(defn ->language-name
  "Map a BCP-47 tag (\"en\", \"en-US\") to the Qwen3-ASR language hint (\"English\")
   via its primary subtag. nil/blank/unmapped => nil (model auto-detect)."
  [language]
  (some-> language str str/trim not-empty
          (str/split #"-") first str/lower-case language-names))

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

(defn- samples->ms [sample sample-rate]
  (long (Math/round (* 1000.0 (/ (double sample) sample-rate)))))

(defn- span->segment [text {:keys [start-ms end-ms language]}]
  (cond-> {:start-ms (or start-ms 0) :end-ms (or end-ms 0) :text text}
    language (assoc :language language)))

(defn transcribe-with-spans
  "Route silero-vad `spans` through `transcribe-fn` [model-key model-dir samples
   language-name => (r/ok text) | (r/err ...)]: slice the samples per span and
   transcribe each slice with the span's OWN :language hint (falling back to the
   port-level `language`). => (r/ok [raw-segments]) — one segment per span at the
   span's bounds (Qwen3-ASR returns plain text, no word timestamps). No spans =>
   ONE whole-clip call with the port-level language."
  [transcribe-fn model-key model-dir samples sample-rate language spans span-pad-ms]
  (if (seq spans)
    (reduce
     (fn [acc-res span]
       (r/let-ok [acc acc-res]
         (let [[start end] (sample-range sample-rate (alength ^floats samples) span-pad-ms span)]
           (if (= start end)
             (r/ok acc)
             (r/let-ok [text (transcribe-fn model-key model-dir
                                            (slice-samples samples start end)
                                            (->language-name (or (:language span) language)))]
               (r/ok (conj acc (span->segment text span))))))))
     (r/ok [])
     spans)
    (r/let-ok [text (transcribe-fn model-key model-dir samples
                                   (->language-name language))]
      (r/ok [{:start-ms 0 :end-ms (samples->ms (alength ^floats samples) sample-rate)
              :text text}]))))

(defrecord Qwen3AsrTranscriber [model-key model-dir span-pad-ms]
  p.asr/ITranscriber
  (transcribe [_ audio-source language opts]
    (if-let [path (sup/audio->path audio-source)]
      (r/let-ok [{:keys [samples sample-rate]} (sup/read-wav-mono-floats path)
                 raw (r/guard Throwable
                              (r/err :error/asr-failed
                                     {:reason "qwen3-asr native backend failed to load"})
                       (let [transcribe-fn @(requiring-resolve native-transcribe-sym)]
                         (transcribe-with-spans transcribe-fn model-key model-dir
                                                samples sample-rate language (:spans opts)
                                                span-pad-ms)))]
        (r/ok {:segments (sup/normalize-segments raw {:unit :ms})}))
      (r/err :error/asr-failed {:reason "audio-source carries no path"}))))

;; --- provider registry (OCP self-registration) ------------------------------

(defmethod reg/resolve-transcriber :qwen3-asr
  [_ config]
  (let [opts        (get config :transcriber-opts)
        model-key   (keyword (get opts :model-key default-model-key))
        model-dir   (:model-dir opts)
        span-pad-ms (long (get opts :span-pad-ms 500))]
    (cond
      (not (backend-present?))
      (r/err :error/transcriber-unavailable
             {:provider :qwen3-asr
              :hint     "add the :qwen3-asr deps alias (org.replikativ/pretrained-rstr) so pretrained.asr is on the classpath"})

      (not (known-model-keys model-key))
      (r/err :error/transcriber-unavailable
             {:provider  :qwen3-asr
              :model-key model-key
              :hint      (str "unknown Qwen3-ASR model key — known: " (pr-str known-model-keys))})

      (and model-dir (not (.isDirectory (File. ^String model-dir))))
      (r/err :error/transcriber-unavailable
             {:provider  :qwen3-asr
              :model-dir model-dir
              :hint      "config [:transcriber-opts :model-dir] names no existing directory — omit it to auto-download sha-pinned weights from HF"})

      :else
      (r/ok (->Qwen3AsrTranscriber model-key model-dir span-pad-ms)))))
