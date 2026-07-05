(ns vtranslate.engine.adapters.transcriber.openai-compatible
  "ITranscriber over any OpenAI-compatible /audio/transcriptions endpoint — the
   REAL, no-native-dep ASR adapter. ONE record parameterized by base URL, model,
   and where the API key comes from; :groq, :openai-whisper and :whisper-server
   (a local whisper.cpp / faster-whisper-server) register themselves (OCP — adding
   another compatible host is one more defmethod). Mirrors the LLM translator: the
   JDK's java.net.http does transport, cheshire parses; a configured `pass:` path
   wins over the env var for the key (a stale env key can't shadow the real one).

   Capability gate at RESOLVE time (not call time): a key-requiring provider with
   NO key resolves to (r/err :error/transcriber-unavailable ...) so the router's
   fallback chain cleanly skips it — never a silent fake. A keyless local server
   is always resolvable. The transcribe method fails LOUD (:error/asr-failed) on
   any transport/parse failure. Response uses verbose_json => per-segment
   timestamps (seconds), normalized to the contract by support/normalize-segments."
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [cheshire.core :as json]
            [hive-dsl.result :as r]
            [vtranslate.engine.port.transcriber :as p.asr]
            [vtranslate.engine.adapters.transcriber.support :as sup]
            [vtranslate.engine.providers.transcriber-registry :as reg])
  (:import [java.io ByteArrayOutputStream File]
           [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers]
           [java.nio.file Files]
           [java.time Duration]))

;; --- secret resolution: pass: ref (authoritative) > env var ----------------

(defn- pass-show [path]
  (try
    (let [{:keys [exit out]} (shell/sh "pass" "show" path)]
      (when (zero? exit) (some-> out str/split-lines first str/trim not-empty)))
    (catch Exception _ nil)))

(defn- resolve-key [secret-env secret-pass]
  (or (when secret-pass (pass-show secret-pass))
      (some-> (System/getenv secret-env) str/trim not-empty)))

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
      (w (str "Content-Disposition: form-data; name=\"" k "\"" crlf crlf))
      (w (str v crlf)))
    (w (str "--" boundary crlf))
    (w (str "Content-Disposition: form-data; name=\"" (:name file)
            "\"; filename=\"" (:filename file) "\"" crlf))
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

(defn segments-from
  "Promote a verbose_json reply into contract segments. When the server returns
   per-segment timestamps use them; when it returns only :text, emit ONE segment
   spanning the whole clip (duration read from the WAV, else 0)."
  [resp fallback-path]
  (let [segs (:segments resp)]
    (if (seq segs)
      (sup/normalize-segments segs {:unit :s})
      (sup/normalize-segments
       [{:start 0
         :end   (/ (or (sup/wav-duration-ms fallback-path) 0) 1000.0)
         :text  (:text resp)}]
       {:unit :s}))))

(defrecord OpenAiTranscriber [api-url model api-key]
  p.asr/ITranscriber
  (transcribe [_ audio-source language _opts]
    (if-let [path (sup/audio->path audio-source)]
      (r/let-ok [bytes (r/try-effect* :error/asr-failed
                         (Files/readAllBytes (.toPath (File. ^String path))))
                 resp  (post-multipart
                        api-url api-key
                        (multipart (cond-> {"model" model "response_format" "verbose_json"}
                                     (seq language) (assoc "language" language))
                                   {:name "file" :filename "audio.wav" :bytes bytes}))]
        (r/ok {:segments (segments-from resp path)}))
      (r/err :error/asr-failed {:reason "audio-source carries no path"}))))

;; --- provider registry ------------------------------------------------------

(def provider-defaults
  "Per-provider endpoint + default model + key source. `:keyless?` marks a local
   server that needs no auth. Overridable via config [:transcriber-opts]."
  {:groq           {:api-url "https://api.groq.com/openai/v1/audio/transcriptions"
                    :secret-env "GROQ_API_KEY"    :model "whisper-large-v3"}
   :openai-whisper {:api-url "https://api.openai.com/v1/audio/transcriptions"
                    :secret-env "OPENAI_API_KEY"  :model "whisper-1"}
   :whisper-server {:api-url "http://127.0.0.1:8080/inference"
                    :secret-env "WHISPER_SERVER_KEY" :model "whisper-1" :keyless? true}})

(defn make-transcriber
  "Build an OpenAiTranscriber for `provider-key`, resolving its key. Per-provider
   overrides (:api-url :model :secret-env :secret-pass) may live under config
   [:transcriber-opts]. => OpenAiTranscriber."
  [provider-key config]
  (let [d    (get provider-defaults provider-key)
        opts (get config :transcriber-opts)
        key  (resolve-key (or (:secret-env opts) (:secret-env d))
                          (:secret-pass opts))]
    (->OpenAiTranscriber (or (:api-url opts) (:api-url d))
                         (or (:model opts) (:model d))
                         key)))

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
