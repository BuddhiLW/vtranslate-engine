(ns vtranslate.engine.adapters.support.llm-chat
  "Shared transport for OpenAI-compatible /chat/completions adapters. Secret
   resolution (a `pass:` ref wins over the env var), one
   pooled HttpClient, a POST returning the assistant message content, and fence
   stripping. Prompt composition + response parsing stay in each adapter — those are
   task-specific; only the wire transport is shared."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [hive-dsl.result :as r]
            [vtranslate.engine.adapters.support.secrets :as secrets])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers)
           (java.math RoundingMode)
           (java.time Duration)))

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

(def resolve-key secrets/resolve-key)

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

(def default-request-timeout-s
  "Per-request ceiling. A batch of subtitle cues through an LLM routinely runs
   past a minute, and a timeout here discards the whole batch, so this is
   generous by design. Override with VT_LLM_TIMEOUT_S."
  300)

(defn request-timeout-s
  "Per-request timeout in seconds, from VT_LLM_TIMEOUT_S when it parses to a
   positive number. => long"
  []
  (or (some-> (System/getenv "VT_LLM_TIMEOUT_S") str parse-long (as-> n (when (pos? n) n)))
      default-request-timeout-s))

(defn- chat-request [api-url api-key body]
  (.. (HttpRequest/newBuilder (URI/create api-url))
      (timeout (Duration/ofSeconds (request-timeout-s)))
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

(defn- decimal [value]
  (when (some? value)
    (try (bigdec value)
         (catch Throwable _ nil))))

(defn- token-count [usage & keys]
  (some #(some-> (get usage %) long) keys))

(defn- usd->micros [usd]
  (some-> (decimal usd)
          (.multiply (bigdec 1000000))
          (.setScale 0 RoundingMode/HALF_UP)
          long))

(defn- token-price [pricing & keys]
  (some #(let [value (get pricing %)]
           (decimal (if (map? value) (:usd value) value)))
        keys))

(defn- calculated-cost-micros [usage pricing]
  (let [input  (token-count usage :prompt_tokens :input_tokens)
        output (token-count usage :completion_tokens :output_tokens)
        in-rate (token-price pricing :prompt :input :prompt-usd-per-token
                             :input-usd-per-token)
        out-rate (token-price pricing :completion :output
                              :completion-usd-per-token :output-usd-per-token)]
    (when (and input output in-rate out-rate)
      (usd->micros (+ (* (bigdec input) in-rate)
                      (* (bigdec output) out-rate))))))

(defn- normalize-usage [usage pricing]
  (when (map? usage)
    (let [input (token-count usage :prompt_tokens :input_tokens)
          output (token-count usage :completion_tokens :output_tokens)
          total (or (token-count usage :total_tokens)
                    (when (and input output) (+ input output)))
          cost (or (some-> (:cost_micros usage) long)
                   (usd->micros (:cost usage))
                   (calculated-cost-micros usage pricing))]
      (cond-> {}
        input (assoc :input-tokens input)
        output (assoc :output-tokens output)
        total (assoc :total-tokens total)
        (some? cost) (assoc :cost-micros cost)))))

(defn- response-data [body pricing]
  (let [payload (json/parse-string body true)]
    {:content (-> payload :choices first :message :content)
     :model (:model payload)
     :usage (normalize-usage (:usage payload) pricing)}))

(defn- report-attempt! [on-attempt common detail]
  (when on-attempt
    (on-attempt (merge common detail))))

(defn- post-chat* [api-url api-key body opts]
  (let [{:keys [max-retries base-delay-ms throttle-ms on-attempt provider model pricing]}
        (merge default-post-opts opts)
        req (chat-request api-url api-key body)]
    (loop [attempt 0]
      (throttle! throttle-ms)
      (let [started-at (now-ms)
            common {:provider provider
                    :model model
                    :request-attempt (inc attempt)
                    :started-at started-at}
            resp (try
                   (send-chat-request req)
                   (catch Throwable throwable
                     (report-attempt! on-attempt common
                                      {:finished-at (now-ms)
                                       :outcome :failed
                                       :error-class (str (class throwable))
                                       :message (.getMessage throwable)})
                     (throw throwable)))
            code (response-status resp)
            pay (response-body resp)]
        (cond
          (<= 200 code 299)
          (let [{:keys [content usage] response-model :model}
                (response-data pay pricing)]
            (report-attempt! on-attempt common
                             {:finished-at (now-ms)
                              :outcome :succeeded
                              :http-status code
                              :model (or response-model model)
                              :usage usage
                              :cost-micros (:cost-micros usage)})
            content)

          (and (retryable-status? code) (< attempt max-retries))
          (do
            (report-attempt! on-attempt common
                             {:finished-at (now-ms)
                              :outcome :retryable-failure
                              :http-status code})
            (sleep! (retry-delay-ms base-delay-ms attempt))
            (recur (inc attempt)))

          :else
          (do
            (report-attempt! on-attempt common
                             {:finished-at (now-ms)
                              :outcome :failed
                              :http-status code})
            (throw (ex-info (str "chat HTTP " code)
                            {:status code
                             :body pay
                             :attempts (inc attempt)}))))))))

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
