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
   - `offset-segments`  — add an offset every raw segment's start/end time,
                         shifting segment times to absolute clip coordinates."
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

(def ^:private max-pad-lead-in 2)

(defn- word-tokens
  "`text`'s whitespace tokens, each paired with the content word it carries
   (nil for bare punctuation)."
  [text]
  (mapv (fn [t] [t (first (content-words t))])
        (remove str/blank? (str/split (str text) #"\s+"))))

(defn trim-pad-overlap
  "`text` without the words it opens on that `previous-text` already closes on:
   the longest run of at least two consecutive words ending `previous-text` that
   `text` repeats, after a lead-in of at most two words. `text` unchanged when
   there is no such run, nil when nothing is left. Case- and
   punctuation-insensitive; the surviving words keep their own spelling."
  [previous-text text]
  (let [prev   (content-words previous-text)
        tokens (word-tokens text)
        worded (vec (keep-indexed (fn [i [_ w]] (when w [i w])) tokens))
        words  (mapv second worded)
        cut    (first (for [lead (range (inc max-pad-lead-in))
                            k    (range (min (count prev) (- (count words) lead))
                                        (dec min-pad-duplicate-run) -1)
                            :when (= (subvec prev (- (count prev) k))
                                     (subvec words lead (+ lead k)))]
                        (first (nth worded (+ lead k -1)))))]
    (if-not cut
      text
      (let [rest (-> (str/join " " (map first (subvec tokens (inc cut))))
                     (str/replace #"\A[\s,;:.\-–—]+" ""))]
        (when-not (str/blank? rest) rest)))))

(defn merge-padded-window
  "Append one padded decode window's `segments` to `acc`, resolving the
   re-transcription a leading pad produces: while a window segment begins before
   `span-start-ms` AND repeats speech the tail of `acc` already carries, only the
   LONGER of the two hypotheses survives — the shorter one is the same utterance
   truncated at a window boundary. A segment that starts inside the pad but
   carries NEW speech is kept without the words it re-hears from the tail of
   `acc` (see `trim-pad-overlap`), and everything after it is kept as is.
   Segment times are absolute ms.
   => [segment ...]"
  [acc span-start-ms segments]
  (loop [acc (vec acc), [head & tail :as remaining] (seq segments)]
    (if (nil? head)
      acc
      (let [previous (peek acc)
            start    (or (:start-ms head) (:start head))
            in-pad?  (and previous start span-start-ms (< start span-start-ms))]
        (cond
          (and in-pad? (pad-duplicate? (:text previous) (:text head)))
          (if (> (count (content-words (:text head)))
                 (count (content-words (:text previous))))
            (recur (conj (pop acc) head) tail)
            (recur acc tail))

          in-pad?
          (if-let [text (trim-pad-overlap (:text previous) (:text head))]
            (recur (into acc (cons (assoc head :text text) tail)) nil)
            (recur acc tail))

          :else
          (recur (into acc remaining) nil))))))

(defn normalize-segments
  "Coerce raw backend segments into the ITranscriber contract shape: ordered,
   non-overlapping, start<=end, non-blank speech text. Preserves optional
   :language. opts: {:unit :s|:ms (default :ms) :default-confidence 1.0}.

   Non-lexical markers (see `non-speech-text?`) are DROPPED here, BEFORE the
   overlap shear — the order is load-bearing. A marker whose span covers the
   silence between two utterances would otherwise push the next real segment's
   start past its own end, shearing genuine speech down to zero extent.

   Overlaps are repaired by TRIMMING THE EARLIER hypothesis back to the later
   one's start, never by pushing the later one forward: an over-long hypothesis
   (whisper times a short window against its padded decode length) would
   otherwise collapse every segment after it to zero extent, and a run of
   zero-extent cues is a subtitle track that simply stops. Only when trimming
   cannot help — the earlier segment starts at or after this one — does the
   later start shear forward as a last resort."
  ([raw] (normalize-segments raw {}))
  ([raw {:keys [unit default-confidence] :or {unit :ms default-confidence 1.0}}]
   (->> raw
        (keep (fn [s]
                (let [start (->ms (or (:start-ms s) (:start s)) unit)
                      end   (->ms (or (:end-ms s) (:end s)) unit)
                      text  (some-> (:text s) str str/trim)]
                  (when (and start (seq text) (not (non-speech-text? text)))
                    ;; a namespaced key is an annotation a route or decorator put
                    ;; on the hypothesis (:asr/..., :segment/...): it rides along
                    (cond-> (into {:start-ms   start
                                   :end-ms     (max start (or end start))
                                   :text       text
                                   :confidence (double (or (:confidence s) default-confidence))}
                                  (filter (fn [[k _]] (and (keyword? k) (namespace k))))
                                  s)
                      (:language s) (assoc :language (:language s)))))))
        (sort-by :start-ms)
        (reduce (fn [acc seg]
                  (let [prev (peek acc)]
                    (cond
                      (or (nil? prev) (<= (:end-ms prev) (:start-ms seg)))
                      (conj acc seg)

                      (< (:start-ms prev) (:start-ms seg))
                      (conj (pop acc)
                            (assoc prev :end-ms (:start-ms seg))
                            seg)

                      :else
                      (let [start (max (:start-ms seg) (:end-ms prev))]
                        (conj acc (assoc seg
                                         :start-ms start
                                         :end-ms   (max start (:end-ms seg))))))))
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

   => (r/ok {:bytes byte-array :offset-ms n :samples int :sample-rate int})
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
          :samples     n
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

(defn- segment-ms
  "One end of a raw segment in absolute ms, whichever spelling the backend used:
   `ms-key` (already ms) or `s-key` (seconds). nil when it carries neither."
  [seg ms-key s-key]
  (cond
    (contains? seg ms-key) (long (get seg ms-key))
    (contains? seg s-key)  (long (Math/round (* 1000.0 (double (get seg s-key)))))
    :else                  nil))

(defn clamp-to-window
  "Confine one decode window's absolute-ms `segments` to the audio that window
   actually carried, [`window-start-ms`, `window-end-ms`].

   whisper pads any window shorter than its fixed decode length out with
   silence, so a backend can time a hypothesis PAST the end of the audio it was
   handed — a 2 s window returning a segment that ends at 4 s is routine. Left
   alone that overshoot outlives its window: it survives the merge, and the
   overlap repair then trims or shears every later segment against a boundary
   that no audio ever justified, which is how a track stops before the clip
   does. A segment starting at or after the window end is pure padding and is
   dropped; one that merely runs over has its end pulled back to the window.

   Both spellings are preserved as they came in (:start-ms/:end-ms in ms,
   :start/:end in seconds). Order is preserved.
   => [segment ...]"
  [window-start-ms window-end-ms segments]
  (let [lo (long window-start-ms)
        hi (long window-end-ms)]
    (if (<= hi lo)
      []
      (into []
            (keep (fn [seg]
                    (let [s (or (segment-ms seg :start-ms :start) lo)
                          e (or (segment-ms seg :end-ms :end) s)]
                      (when (< s hi)
                        (let [s' (max lo s)
                              e' (min hi (max e s'))]
                          (cond-> seg
                            (contains? seg :start-ms) (assoc :start-ms s')
                            (contains? seg :end-ms)   (assoc :end-ms e')
                            (contains? seg :start)    (assoc :start (/ s' 1000.0))
                            (contains? seg :end)      (assoc :end (/ e' 1000.0))))))))
            segments))))

(def decode-knobs
  "The decode knobs a caller may carry in a transcriber's run-opts, each with the
   coercion the backend field takes.

   A knob that is ABSENT is left alone, so the backend's own default stands, and
   whisper.cpp's defaults already ARE the fallback rule the Whisper paper
   describes: temperature 0.0 rising by 0.4, entropy threshold 2.4,
   average-logprob threshold -1.0, no-speech threshold 0.6, and no-context TRUE,
   so a hypothesis is never conditioned on the previous window's text and cannot
   be carried forward into the next. None of that is a default of ours to tune.
   A knob exists for a caller who MEASURED that another value reads better on the
   corpus — it is not a dial to turn on a hunch.

   Lives here, core-safe, rather than beside the JNI imports, so the resolution
   is testable without the native library on the classpath."
  {:beam-size                   int
   :best-of                     int
   :temperature                 float
   :temperature-inc             float
   :entropy-thold               float
   :logprob-thold               float
   :no-speech-thold             float
   :suppress-blank?             boolean
   ;; whisper.cpp can suppress the NON-SPEECH token class at decode time: the
   ;; "(Music)" / "[APPLAUSE]" / caption-credit family. The binding leaves it
   ;; OFF, which is why such text reaches the transcript at all and has to be
   ;; recognised downstream.
   :suppress-non-speech-tokens? boolean
   :no-context?                 boolean
   :initial-prompt              str})

(defn resolve-decode-opts
  "The decode knobs `run-opts` actually asks for, coerced to the type its field
   takes. Unknown keys are ignored; a knob whose value is nil is NOT asked for,
   so it stays absent rather than clearing the backend's default. Pure.
   => {knob coerced-value}"
  [run-opts]
  (reduce-kv (fn [acc knob coerce]
               (if-some [v (get run-opts knob)]
                 (assoc acc knob (coerce v))
                 acc))
             {}
             decode-knobs))
