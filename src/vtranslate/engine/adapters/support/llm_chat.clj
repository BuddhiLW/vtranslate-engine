(ns vtranslate.engine.adapters.support.llm-chat
  "Shared transport for OpenAI-compatible /chat/completions adapters. Secret
   resolution (a `pass:` ref wins over the env var), one
   pooled HttpClient, a POST returning the assistant message content, and fence
   stripping. Prompt composition + response parsing stay in each adapter — those are
   task-specific; only the wire transport is shared."
  (:require [cheshire.core :as json]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [hive-dsl.result :as r])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers)
           (java.time Duration)))

(defn- pass-show
  "First line of `pass show <path>`, or nil (missing pass / non-zero exit)."
  [path]
  (try
    (let [{:keys [exit out]} (shell/sh "pass" "show" path)]
      (when (zero? exit) (some-> out str/split-lines first str/trim not-empty)))
    (catch Exception _ nil)))

(defonce ^:private pass-cache
  (atom {}))

(defonce ^:private last-request-at
  (atom 0))

(def ^:private default-post-opts
  {:max-retries 2
   :base-delay-ms 250
   :throttle-ms 250})

(defn- now-ms []
  (System/currentTimeMillis))

(defn- sleep! [ms]
  (when (pos? ms)
    (Thread/sleep ms)))

(defn- cached-pass-show [path]
  (when path
    (if-let [cached (get @pass-cache path)]
      cached
      (when-let [secret (pass-show path)]
        (swap! pass-cache assoc path secret)
        secret))))

(defn resolve-key
  "A configured `pass:` path wins over the env var (a stale env key must not shadow
   the real one). Successful pass lookups are cached per JVM. => key-string | nil."
  [secret-env secret-pass]
  (or (cached-pass-show secret-pass)
      (some-> (System/getenv secret-env) str/trim not-empty)))

(def ^:private http-client
  (delay (.. (HttpClient/newBuilder) (connectTimeout (Duration/ofSeconds 15)) (build))))

(defn chat-body
  "OpenAI chat request body from a `system` + `user` message string.
   opts: :temperature (default 0.2)."
  [model system user {:keys [temperature] :or {temperature 0.2}}]
  (json/generate-string
   {:model       model
    :temperature temperature
    :messages    [{:role "system" :content system}
                  {:role "user"   :content user}]}))

(defn- retryable-status? [status]
  (or (= 429 status)
      (<= 500 status 599)))

(defn- retry-delay-ms [base-delay-ms attempt]
  (* base-delay-ms (bit-shift-left 1 attempt)))

(defn- throttle! [throttle-ms]
  (when (pos? throttle-ms)
    (let [now (now-ms)
          wait-ms (- (+ @last-request-at throttle-ms) now)]
      (when (pos? wait-ms)
        (sleep! wait-ms))
      (reset! last-request-at (now-ms)))))

(defn- chat-request [api-url api-key body]
  (.. (HttpRequest/newBuilder (URI/create api-url))
      (timeout (Duration/ofSeconds 60))
      (header "Content-Type" "application/json")
      (header "Authorization" (str "Bearer " api-key))
      (POST (HttpRequest$BodyPublishers/ofString body))
      (build)))

(defn- send-chat-request [req]
  (.send ^HttpClient @http-client req (HttpResponse$BodyHandlers/ofString)))

(defn- response-status [resp]
  (if (map? resp)
    (:status resp)
    (.statusCode resp)))

(defn- response-body [resp]
  (if (map? resp)
    (:body resp)
    (.body resp)))

(defn- response-content [body]
  (-> (json/parse-string body true) :choices first :message :content))

(defn- post-chat* [api-url api-key body opts]
  (let [{:keys [max-retries base-delay-ms throttle-ms]}
        (merge default-post-opts opts)
        req (chat-request api-url api-key body)]
    (loop [attempt 0]
      (throttle! throttle-ms)
      (let [resp (send-chat-request req)
            code (response-status resp)
            pay (response-body resp)]
        (cond
          (<= 200 code 299)
          (response-content pay)

          (and (retryable-status? code) (< attempt max-retries))
          (do
            (sleep! (retry-delay-ms base-delay-ms attempt))
            (recur (inc attempt)))

          :else
          (throw (ex-info (str "chat HTTP " code)
                          {:status code
                           :body pay
                           :attempts (inc attempt)})))))))

(defn post-chat
  "POST a chat-completions `body`, returning the assistant message content.
   Retries retryable HTTP statuses (429 and 5xx) with exponential backoff.
   => (r/ok content-string) | (r/err error-kw {:status n :body s :attempts n} | {...})."
  ([error-kw api-url api-key body]
   (post-chat error-kw api-url api-key body nil))
  ([error-kw api-url api-key body opts]
   (try
     (r/ok (post-chat* api-url api-key body opts))
     (catch clojure.lang.ExceptionInfo e
       (r/err error-kw (assoc (ex-data e) :message (.getMessage e))))
     (catch Throwable t
       (r/err error-kw {:class (str (class t))
                        :message (.getMessage t)})))))

(defn strip-fences
  "Strip a leading ```/```json fence and trailing ``` from `s`."
  [s]
  (-> (str/trim (str s))
      (str/replace #"^```(?:json)?\s*" "")
      (str/replace #"\s*```$" "")))