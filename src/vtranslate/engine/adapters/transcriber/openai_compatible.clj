(ns vtranslate.engine.adapters.transcriber.openai-compatible
  "ITranscriber over any OpenAI-compatible /audio/transcriptions endpoint — the
   REAL, no-native-dep ASR adapter. ONE record parameterized by base URL, model,
   key source, and a shared `opts` map that carries per-provider knobs; :groq,
   :openai-whisper and :whisper-server (a local whisper.cpp / faster-whisper-server
   / speaches) register themselves (OCP — adding another compatible host is one
   more defmethod). Mirrors the LLM translator: the JDK's java.net.http does
   transport, cheshire parses; a configured `pass:` path wins over the env var
   for the key (a stale env key can't shadow the real one).

   Capability gate at RESOLVE time (not call time): a key-requiring provider with
   NO key resolves to (r/err :error/transcriber-unavailable ...) so the router's
   fallback chain cleanly skips it — never a silent fake. A keyless local server
   is always resolvable. The transcribe method fails LOUD (:error/asr-failed) on
   any transport/parse failure. Response uses verbose_json => per-segment
   timestamps (seconds), normalized to the contract by support/normalize-segments.

   Anti-hallucination hygiene — the reason this ns is bigger than a plain
   multipart POST:

   - When `(:spans opts)` is present (silero-vad ran upstream), we POST one
     request per span rather than one for the whole clip. Each request is its
     own decoder state, so a hallucination that whisper's greedy decoder loops
     on for a non-speech tail can never PROPAGATE forward into real speech.
     Segments are shifted back to absolute clip time before being merged.
   - Deterministic `temperature=0.0` and a benign empty `prompt` are sent by
     default; both suppress the temperature-fallback path where faster-whisper
     ships a low-quality window rather than a fallback error.
   - Per-segment `no_speech_prob` and `compression_ratio` (verbose_json ships
     both) are filtered through support/drop-hallucinated-windows AFTER the
     merge, catching the classic silent-tail loop the server itself flagged
     as suspect but shipped anyway.
   - Extra form fields under `[:transcriber-opts :extra-form]` are appended
     verbatim, so operator-specific speaches knobs (`hotwords`,
     `without_timestamps=false`, …) reach the server without another code
     change here."
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [hive-dsl.result :as r]
            [vtranslate.engine.port.transcriber :as p.asr]
            [vtranslate.engine.adapters.transcriber.support :as sup]
            [vtranslate.engine.providers.transcriber-registry :as reg]
            [vtranslate.engine.adapters.support.secrets :as secrets])
  (:import [java.io ByteArrayOutputStream File]
           [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers]
           [java.nio.file Files]
           [java.time Duration]))

;; --- secret resolution: pass: ref (authoritative) > env var ----------------

(def ^:private resolve-key secrets/resolve-key)

;; --- multipart/form-data ----------------------------------------------------

(def ^:private http-client
  (delay (.. (HttpClient/newBuilder) (connectTimeout (Duration/ofSeconds 15)) (build))))

(defn- multipart
  "Encode `fields` (string->string) + one `file` part {:name :filename :bytes} as
   multipart/form-data. => {:content-type header-string :body byte-array}."
  [fields file]
  (let [boundary (str "----vtranslate" (Long/toHexString (System/nanoTime)))
        crlf     "\r\n"
        out      (ByteArrayOutputStream.)
        w        (fn [^String s] (.write out (.getBytes s "UTF-8")))]
    (doseq [[k v] fields]
      (w (str "--" boundary crlf))
      (w (str "Content-Disposition: form-data; name="" k """ crlf crlf))
      (w (str v crlf)))
    (w (str "--" boundary crlf))
    (w (str "Content-Disposition: form-data; name="" (:name file)
            ""; filename="" (:filename file) """ crlf))
    (w (str "Content-Type: audio/wav" crlf crlf))
    (.write out ^bytes (:bytes file))
    (w crlf)
    (w (str "--" boundary "--" crlf))
    {:content-type (str "multipart/form-data; boundary=" boundary)
     :body         (.toByteArray out)}))

(defn- post-multipart
  "POST the multipart request, parsing the JSON reply.
   => (r/ok parsed-map) | (r/err :error/asr-failed {...})."
  [api-url api-key {:keys [content-type body]}]
  (r/try-effect* :error/asr-failed
    (let [b    (.. (HttpRequest/newBuilder (URI/create api-url))
                   (timeout (Duration/ofSeconds 300))
                   (header "Content-Type" content-type)
                   (POST (HttpRequest$BodyPublishers/ofByteArray body)))
          _    (when api-key (.header b "Authorization" (str "Bearer " api-key)))
          resp (.send ^HttpClient @http-client (.build b) (HttpResponse$BodyHandlers/ofString))
          code (.statusCode resp)]
      (if (<= 200 code 299)
        (json/parse-string (.body resp) true)
        (throw (ex-info (str "asr HTTP " code) {:status code :body (.body resp)}))))))

;; --- request shaping --------------------------------------------------------

(def default-temperature
  "Match speaches / OpenAI default: `0.0` is the deterministic pass. A higher
   value engages faster-whisper's temperature-fallback loop, which is precisely
   the path that ships a low-quality window rather than failing — the tail we
   want NOT to ship."
  0.0)

(defn- format-temperature ^String [t]
  ;; The endpoint parses "temperature" via `Form(float)`, so send a number
  ;; string. String/valueOf on 0.0 prints "0.0" which is accepted; explicit
  ;; format keeps the wire deterministic for the tests.
  (format "%.2f" (double t)))

(defn- string-form-fields
  "Coerce every extra-form value to a string — multipart/form-data has no other
   type. A nil is dropped so a caller can `{:hotwords nil}` to unset without
   sending an empty header, and non-string non-nil values are `str`-ed. Keys
   may be keywords or strings."
  [m]
  (into {} (keep (fn [[k v]]
                   (when-not (nil? v)
                     [(name k) (str v)])))
        m))

(defn- base-fields
  "Fixed fields every request carries, plus caller-configured opts. `temperature`,
   `prompt` and `response_format` are always sent so the wire is deterministic
   even when the server default drifts. `extra-form` merges last, so a per-call
   override wins over the anti-hallucination baseline."
  [model language {:keys [temperature prompt extra-form]
                   :or   {temperature default-temperature prompt ""}}]
  (cond-> {"model"           model
           "response_format" "verbose_json"
           "temperature"     (format-temperature temperature)
           "prompt"          (str prompt)}
    (seq language) (assoc "language" language)
    (seq extra-form) (merge (string-form-fields extra-form))))

;; --- response shaping -------------------------------------------------------

(defn segments-from
  "Promote a verbose_json reply into contract segments. When the server returns
   per-segment timestamps use them; when it returns only :text, emit ONE segment
   spanning the whole clip (duration read from the WAV, else 0).

   Before normalising, per-segment metrics are used to filter the classic
   Whisper repetition-loop: a window carrying BOTH high :no_speech_prob AND
   high :compression_ratio is what the decoder itself would have suppressed
   had temperature-fallback not exhausted. The filter is fail-open — a reply
   without these metrics is unaffected."
  [resp fallback-path opts]
  (let [segs (:segments resp)]
    (if (seq segs)
      (-> segs
          (sup/drop-hallucinated-windows opts)
          (sup/normalize-segments {:unit :s}))
      (sup/normalize-segments
       [{:start 0
         :end   (/ (or (sup/wav-duration-ms fallback-path) 0) 1000.0)
         :text  (:text resp)}]
       {:unit :s}))))

;; --- span-aware transcription ----------------------------------------------

(defn- transcribe-bytes
  "One POST for one WAV byte-array (either the whole clip or one span slice).
   => (r/ok resp-map) | (r/err :error/asr-failed ...)."
  [{:keys [api-url api-key model]} language opts bytes]
  (post-multipart api-url api-key
                  (multipart (base-fields model language opts)
                             {:name "file" :filename "audio.wav" :bytes bytes})))

(defn- read-all-bytes [path]
  (r/try-effect* :error/asr-failed
    (Files/readAllBytes (.toPath (File. ^String path)))))

(defn- transcribe-whole
  "Legacy path: POST the whole file and shape the reply into contract segments.
   Used when no VAD spans are available."
  [transcriber path language opts]
  (r/let-ok [bytes (read-all-bytes path)
             resp  (transcribe-bytes transcriber language opts bytes)]
    (r/ok {:segments (segments-from resp path opts)})))

(defn- transcribe-one-span
  "Slice `path` to `span`, POST the slice, and return raw verbose_json segments
   shifted into absolute clip time. The hallucination filter runs here — one
   bad span cannot poison the merged output."
  [transcriber path language opts span pad-ms]
  (r/let-ok [{:keys [bytes offset-ms]} (sup/wav-bytes-slice path span pad-ms)]
    (if (zero? (alength ^bytes bytes))
      (r/ok [])
      (r/let-ok [resp (transcribe-bytes transcriber language opts bytes)]
        (r/ok (-> (:segments resp)
                  vec
                  (sup/drop-hallucinated-windows opts)
                  (sup/offset-segments offset-ms)))))))

(defn- transcribe-with-spans
  "Fold the spans, calling `transcribe-one-span` per island and merging the
   results with support/merge-padded-window so overlapping pad audio doesn't
   double-transcribe the last syllable of one span into the first of the next.
   => (r/ok {:segments contract-segments})."
  [transcriber path language opts spans pad-ms]
  (r/let-ok [raw (reduce
                  (fn [acc-res [i span]]
                    (r/let-ok [acc acc-res
                               segs (transcribe-one-span transcriber path language opts span pad-ms)]
                      (r/ok (sup/merge-padded-window acc (:start-ms span)
                                                     ;; merge-padded-window expects :start-ms/:end-ms
                                                     ;; ms; server segments are seconds, so map here.
                                                     (mapv (fn [s]
                                                             (cond-> s
                                                               (contains? s :start) (assoc :start-ms
                                                                                           (long (Math/round (* 1000.0 (double (:start s))))))
                                                               (contains? s :end)   (assoc :end-ms
                                                                                           (long (Math/round (* 1000.0 (double (:end s))))))))
                                                           segs)))))
                  (r/ok [])
                  (map-indexed vector spans))]
    (r/ok {:segments (sup/normalize-segments raw {:unit :ms})})))

(defrecord OpenAiTranscriber [api-url model api-key opts]
  p.asr/ITranscriber
  (transcribe [this audio-source language call-opts]
    (if-let [path (sup/audio->path audio-source)]
      (let [spans   (seq (:spans call-opts))
            pad-ms  (long (or (:span-pad-ms opts) 200))
            ;; Per-call opts win over per-adapter opts win over defaults —
            ;; the same layering the local backend uses.
            merged  (merge opts (select-keys call-opts
                                             [:temperature :prompt :extra-form
                                              :no-speech-thr :compression-ratio-thr]))]
        (if spans
          (transcribe-with-spans this path language merged spans pad-ms)
          (transcribe-whole this path language merged)))
      (r/err :error/asr-failed {:reason "audio-source carries no path"}))))

;; --- provider registry ------------------------------------------------------

(def provider-defaults
  "Per-provider endpoint + default model + key source. `:keyless?` marks a local
   server that needs no auth. Overridable via config [:transcriber-opts]. The
   `:whisper-server` defaults target a local whisper.cpp inference server; the
   speaches deployment overrides `:api-url` + `:model` in the pod's engine
   config file, and both share this same OpenAI-compat wire shape."
  {:groq           {:api-url "https://api.groq.com/openai/v1/audio/transcriptions"
                    :secret-env "GROQ_API_KEY"    :model "whisper-large-v3"}
   :openai-whisper {:api-url "https://api.openai.com/v1/audio/transcriptions"
                    :secret-env "OPENAI_API_KEY"  :model "whisper-1"}
   :whisper-server {:api-url "http://127.0.0.1:8080/inference"
                    :secret-env "WHISPER_SERVER_KEY" :model "whisper-1" :keyless? true}})

(def ^:private adapter-opt-keys
  "Keys pulled from [:transcriber-opts] into the record's `opts` map. Anything
   NOT here (`:api-url`, `:model`, `:secret-env`, `:secret-pass`) is a build
   knob and stays out of the per-call form."
  [:temperature :prompt :extra-form :span-pad-ms
   :no-speech-thr :compression-ratio-thr])

(defn make-transcriber
  "Build an OpenAiTranscriber for `provider-key`, resolving its key. Per-provider
   overrides (:api-url :model :secret-env :secret-pass :temperature :prompt
   :extra-form :span-pad-ms :no-speech-thr :compression-ratio-thr) may live
   under config [:transcriber-opts]. => OpenAiTranscriber."
  [provider-key config]
  (let [d    (get provider-defaults provider-key)
        opts (get config :transcriber-opts)
        key  (resolve-key (or (:secret-env opts) (:secret-env d))
                          (:secret-pass opts))]
    (->OpenAiTranscriber (or (:api-url opts) (:api-url d))
                         (or (:model opts) (:model d))
                         key
                         (select-keys opts adapter-opt-keys))))

(defn- resolve-provider
  "Capability-gated resolve: keyless local server is always ok; a key-requiring
   host resolves only when a key is present, else :error/transcriber-unavailable."
  [provider-key config]
  (let [d (get provider-defaults provider-key)
        t (make-transcriber provider-key config)]
    (if (or (:keyless? d) (:api-key t))
      (r/ok t)
      (r/err :error/transcriber-unavailable
             {:provider provider-key
              :hint     (str "set " (:secret-env d) " (env) or config [:transcriber-opts :secret-pass]")}))))

(defmethod reg/resolve-transcriber :groq           [k config] (resolve-provider k config))
(defmethod reg/resolve-transcriber :openai-whisper [k config] (resolve-provider k config))
(defmethod reg/resolve-transcriber :whisper-server [k config] (resolve-provider k config))
