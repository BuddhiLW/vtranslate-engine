(ns vtranslate.engine.adapters.transcriber.sherpa-onnx-native
  "NATIVE half of the sherpa-onnx adapter — the ONLY namespace here that imports a
   com.k2fsa.sherpa.onnx type. Mirrors collect.ffmpeg relative to collect.port:
   requiring THIS ns triggers the (:import ...) below, which fails on a classpath
   WITHOUT the :sherpa alias. So the core-safe outer ns
   (adapters.transcriber.sherpa-onnx) never (require)s it at load — it
   lazy-(requiring-resolve)s `transcribe-raw` ONLY after its availability probe
   passes. Keeping this file free of engine logic (pure interop, one function)
   keeps the substitutability guarantees in the outer ns.

   HAZARD (JNI, from the sherpa-onnx Java API): OfflineRecognizer and OfflineStream
   are STATEFUL and own OFF-HEAP memory that the GC does NOT reclaim — they expose
   release() and MUST be released explicitly. We build one recognizer + one stream
   per call and release both in a finally (the with-open discipline collect.ffmpeg
   uses for the ffmpeg grabber), so a throw mid-decode still frees native memory.
   Config objects use sherpa's Builder pattern; getResult is called on the
   RECOGNIZER (not the stream); acceptWaveform takes (samples, sampleRate)."
  (:import [com.k2fsa.sherpa.onnx
            OfflineRecognizer OfflineStream OfflineRecognizerResult
            OfflineRecognizerConfig OfflineModelConfig OfflineWhisperModelConfig]))

(defn- ^OfflineRecognizerConfig build-config
  "Assemble an OfflineRecognizerConfig for offline Whisper from the resolved
   `cfg` (absolute :encoder/:decoder/:tokens paths + decode knobs). :language is
   set only when non-blank so a blank leaves whisper in language-auto-detect."
  [{:keys [encoder decoder tokens language task num-threads provider decoding-method]}]
  (let [whisper (cond-> (.. (OfflineWhisperModelConfig/builder)
                            (setEncoder encoder)
                            (setDecoder decoder)
                            (setTask (or task "transcribe")))
                  (and language (seq language)) (.setLanguage language))
        model   (.. (OfflineModelConfig/builder)
                    (setWhisper (.build whisper))
                    (setTokens tokens)
                    (setNumThreads (int (or num-threads 1)))
                    (setProvider (or provider "cpu"))
                    (setDebug false)
                    (build))]
    (.. (OfflineRecognizerConfig/builder)
        (setOfflineModelConfig model)
        (setDecodingMethod (or decoding-method "greedy_search"))
        (build))))

(defn transcribe-raw
  "Decode `samples` (mono float[] in [-1,1]) at `sample-rate` Hz through a
   sherpa-onnx offline recognizer built from `cfg`. => a RAW hypothesis map
   {:text String :tokens (vec String) :timestamps (vec double, SECONDS)} for the
   outer ns's pure segment mapper. Native handles are released in `finally` even
   on throw; the outer ns wraps this in r/try-effect* so any failure is
   :error/asr-failed (fail-loud — never a fake transcript)."
  [cfg ^floats samples ^long sample-rate]
  (let [rec (OfflineRecognizer. (build-config cfg))]
    (try
      (let [stream (.createStream rec)]
        (try
          (.acceptWaveform stream samples (int sample-rate))
          (.decode rec stream)
          (let [^OfflineRecognizerResult res (.getResult rec stream)]
            {:text       (.getText res)
             :tokens     (vec (.getTokens res))
             :timestamps (vec (.getTimestamps res))})
          (finally (.release stream))))
      (finally (.release rec)))))
