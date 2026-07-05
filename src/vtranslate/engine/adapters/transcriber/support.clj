(ns vtranslate.engine.adapters.transcriber.support
  "Shared, PURE support for EVERY ITranscriber adapter — the Liskov guardrail.
   Backends (whisper.cpp via JNI, an OpenAI-compatible server, sherpa-onnx, ONNX
   Runtime, Panama FFM) differ only in HOW they turn a WAV into raw hypotheses;
   mapping those hypotheses onto the port's segment contract is IDENTICAL, so it
   lives here ONCE. Depending on this ns instead of re-deriving the mapping is
   what keeps the whole family substitutable behind ITranscriber (LSP).

   - `audio->path`     — bridge the two shapes an audio-source takes: the bare WAV
                         path string CollectMediaPort returns at runtime, and the
                         {:path ...} map the port-contract mock passes.
   - `normalize-segments` — coerce raw backend segments into the contract shape,
                         ORDERED, NON-OVERLAPPING, start<=end, non-blank text — so
                         any backend passes check-transcriber BY CONSTRUCTION.
   - `wav-duration-ms` / `read-wav-mono-floats` — JDK-only WAV facts + PCM decode
                         (javax.sound.sampled, no dependency) for the in-process
                         native backends and duration-spanning fallbacks."
  (:require [clojure.string :as str]
            [hive-dsl.result :as r])
  (:import [javax.sound.sampled AudioSystem AudioFormat AudioFormat$Encoding]
           [java.io File]))

(defn audio->path
  "The audio-source is opaque (port.media). At runtime it is the WAV path string
   CollectMediaPort returns; the port-contract mock passes {:path ...}. Accept a
   string, a File, or a map with :path. => path string | nil."
  ^String [audio-source]
  (cond
    (string? audio-source)        audio-source
    (instance? File audio-source) (.getPath ^File audio-source)
    (map? audio-source)           (:path audio-source)
    :else                         nil))

(defn- ->ms
  "Coerce a backend time to a non-negative integer millisecond. `unit` is :ms or
   :s (seconds). nil / non-number => nil."
  [t unit]
  (when (number? t)
    (max 0 (long (Math/round (double (case unit :s (* 1000.0 t) :ms t)))))))

(defn normalize-segments
  "Coerce raw backend segments into the ITranscriber contract shape — ORDERED by
   start, NON-OVERLAPPING (each start >= previous end), start<=end, non-blank
   string text — so ANY backend passes check-transcriber by construction. `raw`
   is a seq of maps carrying :start/:end (or :start-ms/:end-ms) in `unit`, :text,
   and optional :confidence. Blank/timeless segments are dropped.
   opts: {:unit :s|:ms (default :ms) :default-confidence 1.0}."
  ([raw] (normalize-segments raw {}))
  ([raw {:keys [unit default-confidence] :or {unit :ms default-confidence 1.0}}]
   (->> raw
        (keep (fn [s]
                (let [start (->ms (or (:start-ms s) (:start s)) unit)
                      end   (->ms (or (:end-ms s) (:end s)) unit)
                      text  (some-> (:text s) str str/trim)]
                  (when (and start (seq text))
                    {:start-ms   start
                     :end-ms     (max start (or end start))
                     :text       text
                     :confidence (double (or (:confidence s) default-confidence))}))))
        (sort-by :start-ms)
        ;; shear overlaps: force each segment to start at or after the prior end
        (reduce (fn [acc seg]
                  (let [start (max (:start-ms seg) (:end-ms (peek acc) (:start-ms seg)))]
                    (conj acc (assoc seg :start-ms start :end-ms (max start (:end-ms seg))))))
                [])
        vec)))

(defn wav-duration-ms
  "Best-effort clip duration in integer ms from a WAV header, else nil."
  [path]
  (when path
    (try
      (with-open [in (AudioSystem/getAudioInputStream (File. ^String path))]
        (let [frames (.getFrameLength in)
              rate   (.getSampleRate (.getFormat in))]
          (when (and (pos? frames) (pos? rate))
            (long (* 1000.0 (/ frames (double rate)))))))
      (catch Throwable _ nil))))

(defn read-wav-mono-floats
  "Decode the PCM WAV at `path` into a mono float[] in [-1.0, 1.0] at its native
   sample rate — the shape whisper.cpp / native backends expect. The pipeline
   extracts 16 kHz mono, but this coerces any PCM WAV to 16-bit mono LE first.
   => (r/ok {:samples float-array :sample-rate int}) | (r/err :error/asr-failed ...)."
  [path]
  (r/try-effect* :error/asr-failed
    (with-open [in (AudioSystem/getAudioInputStream (File. ^String path))]
      (let [rate   (.getSampleRate (.getFormat in))
            target (AudioFormat. AudioFormat$Encoding/PCM_SIGNED rate 16 1 2 rate false)
            bytes  (with-open [ais (AudioSystem/getAudioInputStream target in)]
                     (.readAllBytes ais))
            n      (quot (alength ^bytes bytes) 2)
            fa     (float-array n)]
        (dotimes [i n]
          (let [lo (bit-and (aget ^bytes bytes (* 2 i)) 0xff)
                hi (aget ^bytes bytes (inc (* 2 i)))
                s  (short (bit-or lo (bit-shift-left (int hi) 8)))]
            (aset fa i (float (/ s 32768.0)))))
        {:samples fa :sample-rate (int rate)}))))
