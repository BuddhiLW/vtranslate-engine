(ns vtranslate.engine.adapters.transcriber.openai-compatible
  "ITranscriber over any OpenAI-compatible /audio/transcriptions endpoint — the
   REAL, no-native-dep ASR adapter. ONE record parameterized by base URL, model,
   key source, and a shared `opts` map that carries per-provider knobs; :groq,
   :openai-whisper and :whisper-server (a local whisper.cpp / faster-whisper-server
   / speaches) register themselves (OCP — adding another compatible host is one
   more defmethod). aleph does the multipart transport, cheshire parses; a
   configured `pass:` path wins over the env var for the key (a stale env key
   can't shadow the real one).

   Capability gate at RESOLVE time (not call time): a key-requiring provider with
   NO key resolves to (r/err :error/transcriber-unavailable ...) so the router's
   fallback chain cleanly skips it — never a silent fake. A keyless local server
   is always resolvable. The transcribe method fails LOUD (:error/asr-failed) on
   any transport/parse failure. Response uses verbose_json => per-segment
   timestamps (seconds), normalized to the contract by support/normalize-segments.

   Anti-hallucination hygiene — the reason this ns is bigger than a plain
   multipart POST:

   - When `(:spans opts)` is present AND `[:transcriber-opts :slice-spans? true]` is
     configured (opt-in, appropriate only with a VAD segmenter because grid spans cut
     utterances mid-word), we POST one request per span rather than one for the whole
     clip. Each request is its own decoder state, so a hallucination that whisper's
     greedy decoder loops on for a non-speech tail can never PROPAGATE forward into
     real speech. Segments are shifted back to absolute clip time before being merged.
   - `temperature` and `prompt` are sent ONLY when explicitly configured (opt-in,
     opt-out silence). Each reply's raw verbose_json segments, metrics attached,
     go through the optional `:asr/clean` call-opt hook before normalising.
   - Extra form fields under `[:transcriber-opts :extra-form]` are appended
     verbatim, so operator-specific speaches knobs (`hotwords`,
     `without_timestamps=false`, …) reach the server without another code
     change here."
  (:require [aleph.http :as http]
            [clojure.string :as str]
            [cheshire.core :as json]
            [hive-dsl.result :as r]
            [vtranslate.engine.port.transcriber :as p.asr]
            [vtranslate.engine.adapters.transcriber.support :as sup]
            [vtranslate.engine.providers.transcriber-registry :as reg]
            [vtranslate.engine.adapters.support.secrets :as secrets]
            [vtranslate.engine.residency :as residency]
            [vtranslate.engine.adapters.model-host.speaches :as speaches]
            [vtranslate.engine.calc.word-cues :as word-cues])
  (:import [java.io File]
           [java.nio.file Files]))

;; --- secret resolution: pass: ref (authoritative) > env var ----------------

(def ^:private resolve-key secrets/resolve-key)

;; --- multipart/form-data ----------------------------------------------------

(def ^:private timeouts
  {:connection-timeout 15000 :request-timeout 300000})

(defn multipart
  "`fields` (string->string) + one `file` part {:name :filename :bytes} as
   aleph :multipart parts, the file sent as audio/wav. => [part ...]"
  [fields file]
  (conj (mapv (fn [[k v]] {:part-name k :content v}) fields)
        {:part-name (:name file)
         :file-name (:filename file)
         :mime-type "audio/wav"
         :content   (:bytes file)}))

(defn- post-multipart
  "POST the multipart `parts`, parsing the JSON reply.
   => (r/ok parsed-map) | (r/err :error/asr-failed {...})."
  [api-url api-key parts]
  (r/try-effect* :error/asr-failed
    (let [{:keys [status body]} @(http/post api-url
                                            (cond-> (assoc timeouts
                                                           :multipart parts
                                                           :throw-exceptions false)
                                              api-key (assoc :headers {"Authorization" (str "Bearer " api-key)})))
          text (some-> body slurp)]
      (if (<= 200 status 299)
        (json/parse-string text true)
        (throw (ex-info (str "asr HTTP " status) {:status status :body text}))))))

;; --- request shaping --------------------------------------------------------

(defn- format-temperature ^String [t]
  ;; The endpoint parses "temperature" via `Form(float)`, so send a number
  ;; string. Use Locale.ROOT so a pt-BR JVM does not send "0,20".
  (String/format java.util.Locale/ROOT "%.2f" (object-array [(double t)])))

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
  "Fixed fields every request carries, plus caller-configured opts. `temperature`
   is sent ONLY when a value was explicitly configured (key present and non-nil);
   `prompt` is sent ONLY when a non-blank prompt was configured. `model`,
   `response_format`, `language` (when non-blank) and `extra-form` behave as now."
  [model language opts]
  (let [temperature (:temperature opts)
        prompt      (:prompt opts)
        extra-form  (:extra-form opts)]
    (cond-> {"model"           model
             "response_format" "verbose_json"}
      (some? (:temperature opts)) (assoc "temperature" (format-temperature temperature))
      (and (contains? opts :prompt) (not (str/blank? (str prompt))))
      (assoc "prompt" (str prompt))
      (seq language) (assoc "language" language)
      (seq extra-form) (merge (string-form-fields extra-form)))))

;; --- response shaping -------------------------------------------------------

(defn segments-from
  "Promote a verbose_json reply into contract segments. When the server returns
   per-segment timestamps use them; when it returns only :text, emit ONE segment
   spanning the whole clip (duration read from the WAV, else 0). Timestamped
   segments pass through the port's `:asr/clean` hook first, while their
   per-segment metrics are still attached."
  [resp fallback-path opts]
  (let [segs (:segments resp)]
    (if (seq segs)
      (-> (p.asr/cleaned opts segs)
          (sup/normalize-segments {:unit :s}))
      (sup/normalize-segments
       [{:start 0
         :end   (/ (or (sup/wav-duration-ms fallback-path) 0) 1000.0)
         :text  (:text resp)}]
       {:unit :s}))))

;; --- span-aware transcription ----------------------------------------------

(def ^:private granularity-fields
  "Word and segment timestamps both: a server asked for words alone may drop
   its segments, and the segments carry the metrics the hallucination filters
   read. The field repeats, so these are pairs, not a map."
  [["timestamp_granularities[]" "segment"]
   ["timestamp_granularities[]" "word"]])

(defn- transcribe-bytes
  "One POST for one WAV byte-array (either the whole clip or one span slice),
   naming server model `model`, else the adapter's own, to endpoint `api-url`,
   else the adapter's own. Another endpoint is sent no key: the adapter's key
   belongs to its own host. Word timestamps are asked for unless `(:cues opts)`
   is false.
   => (r/ok resp-map) | (r/err :error/asr-failed ...)."
  ([transcriber model language opts bytes]
   (transcribe-bytes transcriber model nil language opts bytes))
  ([{:keys [api-key] :as transcriber} model api-url language opts bytes]
   (post-multipart (or api-url (:api-url transcriber))
                   (when-not api-url api-key)
                   (multipart (cond-> (vec (base-fields (or model (:model transcriber)) language opts))
                                (not (false? (:cues opts))) (into granularity-fields))
                              {:name "file" :filename "audio.wav" :bytes bytes}))))

(defn- detected-language
  "The ISO 639 code a verbose_json reply says it heard, when the request named no
   language and the reply carries a code rather than a language name. => s | nil"
  [resp language]
  (when (str/blank? language)
    (let [heard (some-> (:language resp) str str/trim str/lower-case)]
      (when (and heard (re-matches #"[a-z]{2,3}" heard)) heard))))

(defn- reply-raw
  "A verbose_json reply as raw segments in seconds: its own segments, else one
   segment spanning `duration-s` carrying the reply text, recut into
   subtitle-sized segments by the reply's word timestamps unless `(:cues opts)`
   is false (calc.word-cues; a map there overrides its policy). Each carries
   the language the server detected when the request named none."
  [resp duration-s language opts]
  (let [heard (detected-language resp language)
        segs  (vec (or (seq (:segments resp))
                       [{:start 0 :end duration-s :text (:text resp)}]))
        cues  (:cues opts)
        segs  (if (false? cues) segs (word-cues/recut segs (:words resp) (when (map? cues) cues)))]
    (cond->> segs heard (mapv #(assoc % :language heard)))))

(defn- resident!
  "Make server model `model` (else the adapter's own) resident through the
   adapter's residency owner, telling the notice channel what it unloaded. A
   window for another endpoint, or an adapter without an owner, needs nothing.
   => Result"
  [transcriber model api-url]
  (if-let [owner (when-not api-url (get-in transcriber [:opts :residency-owner]))]
    (r/let-ok [{:keys [evicted] :as done} (residency/ensure! owner (or model (:model transcriber)))]
      (when (seq evicted)
        (p.asr/notice! (str "asr-residency: unloaded " (str/join ", " evicted)
                            " for " (:model done))))
      (r/ok done))
    (r/ok nil)))

(defn- window-decoder
  "The `decode` the :asr/route-window hook is handed for one POSTed window of
   `bytes` lasting `duration-s`. [lang] decodes with the adapter's model; [lang
   {:keys [model api-url]}] names server model `model` and/or sends the window
   to endpoint `api-url` instead when given. Other decode opts cannot be served
   over HTTP and are ignored, and the fn carries no :asr/window-ms metadata,
   which is how a route learns that. A window bound for the adapter's own
   server first has its model made resident by the adapter's residency owner,
   when it has one. The reply comes back recut to subtitle size (reply-raw),
   so the window's length never becomes a cue's.
   => (fn ([lang]) ([lang decode-opts])) returning Result<raw seconds>"
  [transcriber opts bytes duration-s]
  (fn decode
    ([lang] (decode lang nil))
    ([lang {:keys [model api-url]}]
     (r/let-ok [_    (resident! transcriber model api-url)
                resp (transcribe-bytes transcriber model api-url lang opts bytes)]
       (r/ok (reply-raw resp duration-s lang opts))))))

(defn- read-all-bytes [path]
  (r/try-effect* :error/asr-failed
    (Files/readAllBytes (.toPath (File. ^String path)))))

(defn- transcribe-whole
  "Legacy path: POST the whole file as one window, through the port's hooks, and
   shape the reply into contract segments. Used when no VAD spans are available."
  [transcriber path language opts]
  (r/let-ok [bytes (read-all-bytes path)
             raw   (p.asr/decoded opts
                                  (window-decoder transcriber opts bytes
                                                  (/ (or (sup/wav-duration-ms path) 0) 1000.0))
                                  language)]
    (r/ok {:segments (sup/normalize-segments raw {:unit :s})})))

(defn- transcribe-one-span
  "Slice `path` to `span`, POST the slice through the port's hooks, and return
   raw verbose_json segments shifted into absolute clip time and confined to the
   audio the slice actually carried. The hallucination filter runs here — one
   bad span cannot poison the merged output; so does the window clamp, because a
   whisper server pads a short slice out to its decode length and can time a
   hypothesis past the end of the audio it was sent. The route is handed the
   span as :asr/span metadata on its decode."
  [transcriber path language opts span pad-ms]
  (r/let-ok [{:keys [bytes offset-ms samples sample-rate]} (sup/wav-bytes-slice path span pad-ms)]
    (if (zero? samples)
      (r/ok [])
      (let [duration-s (/ (double samples) sample-rate)]
        (r/let-ok [segs (p.asr/decoded opts
                                       (vary-meta (window-decoder transcriber opts bytes duration-s)
                                                  assoc :asr/span span)
                                       language)]
          (let [window-end (+ (long offset-ms) (long (Math/round (* 1000.0 duration-s))))]
            (r/ok (sup/clamp-to-window offset-ms window-end
                                       (sup/offset-segments offset-ms segs)))))))))

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
  p.asr/IDeclaresHooks
  (hooks-honoured [_] #{:asr/route-window :asr/clean})

  p.asr/ITranscriber
  (transcribe [this audio-source language call-opts]
    (if-let [path (sup/audio->path audio-source)]
      (let [pad-ms  (long (or (:span-pad-ms opts) 200))
            ;; Per-call opts win over per-adapter opts win over defaults —
            ;; the same layering the local backend uses.
            merged  (merge opts (select-keys call-opts
                                             [:temperature :prompt :extra-form
                                              :slice-spans? :asr/clean
                                              :asr/route-window]))
            spans   (seq (:spans call-opts))]
        (if (and (:slice-spans? merged) spans)
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
   knob and stays out of the per-call form. `:cues` is false to keep the
   server's own segment timing, or a calc.word-cues policy map."
  [:temperature :prompt :extra-form :span-pad-ms
   :slice-spans? :cues])

(defn make-transcriber
  "Build an OpenAiTranscriber for `provider-key`, resolving its key. Per-provider
   overrides (:api-url :model :secret-env :secret-pass :temperature :prompt
   :extra-form :span-pad-ms) may live
   under config [:transcriber-opts]. A :residency policy there (see
   vtranslate.engine.residency) gives the adapter the owner of its speaches
   server's model budget, one per server and policy. => OpenAiTranscriber."
  [provider-key config]
  (let [d       (get provider-defaults provider-key)
        opts    (get config :transcriber-opts)
        key     (resolve-key (or (:secret-env opts) (:secret-env d))
                             (:secret-pass opts))
        api-url (or (:api-url opts) (:api-url d))]
    (->OpenAiTranscriber api-url
                         (or (:model opts) (:model d))
                         key
                         (cond-> (select-keys opts adapter-opt-keys)
                           (:residency opts)
                           (assoc :residency-owner
                                  (residency/shared-owner api-url (:residency opts)
                                                          #(speaches/host api-url)))))))

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
