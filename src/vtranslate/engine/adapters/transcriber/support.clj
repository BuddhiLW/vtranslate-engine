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
   - `non-speech-text?` — is a hypothesis a bracketed non-lexical annotation
                         ([BLANK_AUDIO], (silence), *laughs*) rather than speech?
   - `normalize-segments` — coerce raw backend segments into the contract shape,
                         ORDERED, NON-OVERLAPPING, start<=end, non-blank speech
                         text — any backend passes check-transcriber BY CONSTRUCTION.
   - `wav-duration-ms` / `read-wav-mono-floats` — JDK-only WAV facts + PCM decode
                         (javax.sound.sampled, no dependency) for the in-process
                         native backends and duration-spanning fallbacks.
   - `write-wav-mono!`  — the inverse: float samples back to a 16-bit PCM WAV, for
                         backends whose entry point takes a PATH, not a buffer.
   - `wav-bytes-slice`  — take a time sub-range out of a WAV file as a fresh
                         16-bit PCM WAV byte-array; the JDK equivalent of
                         `ffmpeg -ss ... -to ... -c:a pcm_s16le`. Lets the
                         OpenAI-compatible ASR adapter honour `:spans` by
                         POSTing one request per speech island instead of the
                         whole clip (each request is its own decoder state, so
                         a hallucination on the non-speech tail can never
                         propagate forward).
   - `hallucination-window?` / `drop-hallucinated-windows` — faster-whisper's
                         verbose_json ships per-segment `no_speech_prob` and
                         `compression_ratio`; when the decoder yields but marks
                         the window suspect, drop it here rather than let a
                         looped fragment reach the transcript."
  (:require [clojure.string :as str]
            [hive-dsl.result :as r])
  (:import [javax.sound.sampled AudioSystem AudioFileFormat$Type AudioFormat
            AudioFormat$Encoding AudioInputStream]
           [java.io ByteArrayInputStream ByteArrayOutputStream File]))

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

(def non-speech-markers
  "Words that mark the ABSENCE of speech when they carry a whole annotation.
   Matched as whole words, so both [BLANK_AUDIO] and [ blank audio ] qualify,
   and so does the descriptive form whisper favours, (upbeat music)."
  #{"blank audio" "silence" "silent" "no speech" "inaudible" "unintelligible"
    "music" "noise" "applause" "laughter" "laughs" "static"})

(def ^:private annotation-re
  #"(?s)\A\s*[\[(*](.*)[\])*]\s*\z")

(def ^:private marker-re
  (re-pattern (str "\\b(?:" (str/join "|" non-speech-markers) ")\\b")))

(defn non-speech-text?
  "True when `text` carries no transcribed speech: it is blank, or it is ENTIRELY
   one bracketed/parenthesised/asterisked annotation built from `non-speech-markers`
   — [BLANK_AUDIO], [ Silence ], (upbeat music), *laughs*. An annotation of any
   other wording is left alone, so a backend free to emit bracketed hypotheses
   (the stub transcriber does) is never silently erased."
  [text]
  (let [t (some-> text str str/trim)]
    (boolean
     (or (str/blank? (str t))
         (when-let [body (second (re-find annotation-re t))]
           (let [body (-> body str/lower-case (str/replace #"[^a-z0-9]+" " ") str/trim)]
             (or (str/blank? body)
                 (re-find marker-re body))))))))

(def ^:private min-pad-duplicate-run 2)

(def ^:private min-pad-duplicate-ratio 0.5)

(defn- content-words
  [text]
  (vec (re-seq #"[\p{L}\p{N}']+" (str/lower-case (str text)))))

(defn- longest-common-run
  "Length of the longest run of CONSECUTIVE words occurring in both `a` and `b`."
  [a b]
  (let [n (count a) m (count b)]
    (loop [i 0, prev (vec (repeat (inc m) 0)), best 0]
      (if (= i n)
        best
        (let [[row best'] (loop [j 0, row [0], best best]
                            (if (= j m)
                              [row best]
                              (let [v (if (= (nth a i) (nth b j)) (inc (nth prev j)) 0)]
                                (recur (inc j) (conj row v) (max best v)))))]
          (recur (inc i) row best'))))))

(defn pad-duplicate?
  "True when `text` merely RE-TRANSCRIBES speech `previous-text` already carries:
   at least two consecutive words occur in both AND they account for at least
   half of `text`. Case- and punctuation-insensitive."
  [previous-text text]
  (let [candidate (content-words text)
        run       (longest-common-run (content-words previous-text) candidate)]
    (and (>= run min-pad-duplicate-run)
         (pos? (count candidate))
         (>= (/ (double run) (count candidate)) min-pad-duplicate-ratio))))

(defn merge-padded-window
  "Append one padded decode window's `segments` to `acc`, resolving the
   re-transcription a leading pad produces: while a window segment begins before
   `span-start-ms` AND repeats speech the tail of `acc` already carries, only the
   LONGER of the two hypotheses survives — the shorter one is the same utterance
   truncated at a window boundary. Segment times are absolute ms. A segment that
   starts inside the pad but carries NEW speech is kept, and so is everything
   from the first non-duplicate onwards.
   => [segment ...]"
  [acc span-start-ms segments]
  (loop [acc (vec acc), [head & tail :as remaining] (seq segments)]
    (if (nil? head)
      acc
      (let [previous (peek acc)
            start    (or (:start-ms head) (:start head))]
        (if (and previous start span-start-ms
                 (< start span-start-ms)
                 (pad-duplicate? (:text previous) (:text head)))
          (if (> (count (content-words (:text head)))
                 (count (content-words (:text previous))))
            (recur (conj (pop acc) head) tail)
            (recur acc tail))
          (recur (into acc remaining) nil))))))

(defn normalize-segments
  "Coerce raw backend segments into the ITranscriber contract shape: ordered,
   non-overlapping, start<=end, non-blank speech text. Preserves optional
   :language. opts: {:unit :s|:ms (default :ms) :default-confidence 1.0}.

   Non-lexical markers (see `non-speech-text?`) are DROPPED here, BEFORE the
   overlap shear — the order is load-bearing. A marker whose span covers the
   silence between two utterances would otherwise push the next real segment's
   start past its own end, shearing genuine speech down to zero extent."
  ([raw] (normalize-segments raw {}))
  ([raw {:keys [unit default-confidence] :or {unit :ms default-confidence 1.0}}]
   (->> raw
        (keep (fn [s]
                (let [start (->ms (or (:start-ms s) (:start s)) unit)
                      end   (->ms (or (:end-ms s) (:end s)) unit)
                      text  (some-> (:text s) str str/trim)]
                  (when (and start (seq text) (not (non-speech-text? text)))
                    (cond-> {:start-ms   start
                             :end-ms     (max start (or end start))
                             :text       text
                             :confidence (double (or (:confidence s) default-confidence))}
                      (:language s) (assoc :language (:language s)))))))
        (sort-by :start-ms)
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

(defn write-wav-mono!
  "Write mono float `samples` in [-1.0, 1.0] to `path` as a 16-bit PCM WAV at
   `sample-rate`. The inverse of read-wav-mono-floats, for backends that take a
   file path rather than a sample buffer.
   => (r/ok path) | (r/err :error/asr-failed ...)."
  [path ^floats samples sample-rate]
  (r/try-effect* :error/asr-failed
    (let [n     (alength samples)
          bytes (byte-array (* 2 n))]
      (dotimes [i n]
        (let [clamped (-> (aget samples i) (max -1.0) (min 1.0))
              s       (int (Math/round (* clamped 32767.0)))]
          (aset-byte bytes (* 2 i) (unchecked-byte (bit-and s 0xff)))
          (aset-byte bytes (inc (* 2 i)) (unchecked-byte (bit-and (bit-shift-right s 8) 0xff)))))
      (let [format (AudioFormat. AudioFormat$Encoding/PCM_SIGNED
                                 (float sample-rate) 16 1 2 (float sample-rate) false)]
        (with-open [stream (AudioInputStream. (ByteArrayInputStream. bytes) format n)]
          (AudioSystem/write stream AudioFileFormat$Type/WAVE (File. ^String (str path)))))
      (str path))))

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

(defn samples->wav-bytes
  "Encode mono float `samples` in [-1.0, 1.0] as a 16-bit PCM WAV byte-array at
   `sample-rate`. The in-memory sibling of `write-wav-mono!`. Callers that ship
   audio over HTTP (the OpenAI-compatible adapter) get their bytes without a
   temp file touching disk. => byte-array."
  ^bytes [^floats samples sample-rate]
  (let [n     (alength samples)
        bytes (byte-array (* 2 n))]
    (dotimes [i n]
      (let [clamped (-> (aget samples i) (max -1.0) (min 1.0))
            s       (int (Math/round (* clamped 32767.0)))]
        (aset-byte bytes (* 2 i) (unchecked-byte (bit-and s 0xff)))
        (aset-byte bytes (inc (* 2 i)) (unchecked-byte (bit-and (bit-shift-right s 8) 0xff)))))
    (let [format (AudioFormat. AudioFormat$Encoding/PCM_SIGNED
                               (float sample-rate) 16 1 2 (float sample-rate) false)
          baos   (ByteArrayOutputStream.)]
      (with-open [stream (AudioInputStream. (ByteArrayInputStream. bytes) format n)]
        (AudioSystem/write stream AudioFileFormat$Type/WAVE baos))
      (.toByteArray baos))))

(defn- pad-clip-sample-range
  "Given a decoded WAV of `total-samples` at `sample-rate` and a span in ms, pad
   both ends by `pad-ms`, then clip to [0, total-samples]. => [start-sample
   end-sample], with `start-sample <= end-sample`."
  [total-samples sample-rate pad-ms {:keys [start-ms end-ms]}]
  (let [start-ms (max 0 (- (or start-ms 0) (or pad-ms 0)))
        end-ms   (+ (or end-ms 0) (or pad-ms 0))
        start    (long (Math/floor (* sample-rate (/ (double start-ms) 1000.0))))
        end      (long (Math/ceil (* sample-rate (/ (double end-ms) 1000.0))))
        start    (max 0 (min (long total-samples) start))
        end      (max start (min (long total-samples) end))]
    [start end]))

(defn wav-bytes-slice
  "Extract a time sub-range of the WAV at `path` as a fresh 16-bit PCM mono WAV
   byte-array. `span` is `{:start-ms n :end-ms n}`; both ends are padded by
   `pad-ms` (default 0) and then clipped to the clip's real duration — a pad
   past the tail can NEVER draw silence in that isn't there, which is the whole
   point of chunking here.

   => (r/ok {:bytes byte-array :offset-ms n :sample-rate int})
      | (r/err :error/asr-failed {:reason ...}).

   `:offset-ms` is the absolute clip time of `bytes[0]` after padding/clipping,
   so a caller offsetting per-segment timestamps has one number to add."
  ([path span] (wav-bytes-slice path span 0))
  ([path span pad-ms]
   (r/let-ok [{:keys [^floats samples sample-rate]} (read-wav-mono-floats path)]
     (r/try-effect* :error/asr-failed
       (let [total     (alength samples)
             [s e]     (pad-clip-sample-range total sample-rate pad-ms span)
             n         (max 0 (- e s))
             sliced    (float-array n)
             _         (when (pos? n) (System/arraycopy samples s sliced 0 n))
             offset-ms (long (Math/round (* 1000.0 (/ (double s) sample-rate))))]
         {:bytes       (samples->wav-bytes sliced sample-rate)
          :offset-ms   offset-ms
          :sample-rate (int sample-rate)})))))

(defn offset-segments
  "Add `offset-ms` to every raw segment's start/end field, whatever spelling the
   backend uses (:start-ms/:end-ms or :start/:end). Segments that omit a field
   are left untouched. => vector."
  [offset-ms raw]
  (let [ofs (long offset-ms)]
    (mapv (fn [seg]
            (cond-> seg
              (contains? seg :start-ms) (update :start-ms + ofs)
              (contains? seg :end-ms)   (update :end-ms + ofs)
              (contains? seg :start)    (update :start + (/ ofs 1000.0))
              (contains? seg :end)      (update :end + (/ ofs 1000.0))))
          raw)))

;; --- hallucination heuristics on verbose_json ------------------------------
;;
;; faster-whisper (what speaches wraps) ships two per-segment numbers that call
;; a window suspect but ships it anyway once temperature fallback is exhausted:
;;
;;   :no_speech_prob   — P(this window is silence). Anything above ~0.6 is the
;;                       decoder saying "I hallucinated words over silence."
;;   :compression_ratio — token-repetition rate. >2.4 is the loop signature: the
;;                       same phrase transcribed over and over. faster-whisper
;;                       itself defaults to :compression_ratio_threshold 2.4 for
;;                       the temperature-fallback trigger, so this is exactly
;;                       the boundary the model considers "too repetitive."
;;
;; A key-only server never lets the client pass these through, but we can still
;; filter them AFTER the fact — the non-speech tail's hallucination window is
;; the one segment carrying both markers together, and dropping it costs us
;; nothing that isn't already garbage.

(def default-no-speech-threshold
  "Match faster-whisper's own default: at :no_speech_prob >= 0.6 the decoder
   itself would suppress the window when :log_prob_threshold also fires. Kept
   as a def rather than inline so a test/operator can adjust in one place."
  0.6)

(def default-compression-ratio-threshold
  "Match faster-whisper's own default: >2.4 is where its temperature-fallback
   loop declares the window a repetition-loop. A segment that clears this bar
   after fallback exhausted is the hallucination we want to drop."
  2.4)

(defn- ->double [x]
  (when (number? x) (double x)))

(defn hallucination-window?
  "True when a raw verbose_json segment carries BOTH markers a Whisper decoder
   uses to call a window a repetition-loop: no_speech_prob at/above
   `no-speech-thr` AND compression_ratio at/above `compression-ratio-thr`. Both
   markers together — one alone is a false positive (a legitimately quiet or a
   legitimately repetitive segment); together they mean the model babbled over
   silence, which is the exact tail-hallucination we filter. Kebab-cased keys
   accepted too, so a re-shaped upstream still reaches this check."
  ([seg] (hallucination-window? seg default-no-speech-threshold default-compression-ratio-threshold))
  ([seg no-speech-thr compression-ratio-thr]
   (let [nsp (->double (or (:no_speech_prob seg) (:no-speech-prob seg)))
         cr  (->double (or (:compression_ratio seg) (:compression-ratio seg)))]
     (boolean (and nsp cr
                   (>= nsp (double no-speech-thr))
                   (>= cr  (double compression-ratio-thr)))))))

(defn drop-hallucinated-windows
  "Filter `raw-segments` through `hallucination-window?`. A segment that carries
   neither marker (either because the backend does not surface them or because
   the values were absent) passes through — the guard is fail-OPEN: it only
   drops what it can PROVE is a decoder-flagged repetition, so a plain-text
   response (no per-segment metrics) is unaffected. => vector."
  ([raw-segments] (drop-hallucinated-windows raw-segments {}))
  ([raw-segments {:keys [no-speech-thr compression-ratio-thr]
                  :or   {no-speech-thr default-no-speech-threshold
                         compression-ratio-thr default-compression-ratio-threshold}}]
   (into [] (remove #(hallucination-window? % no-speech-thr compression-ratio-thr)) raw-segments)))
