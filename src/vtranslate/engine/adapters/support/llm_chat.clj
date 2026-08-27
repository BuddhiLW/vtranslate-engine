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

;; Measured 2026-08-27 against Venice deepseek-v3.2: roughly half of a short
;; run of jobs died on HTTP 429 "the model is currently overloaded". At the
;; previous 2 retries / 250ms base the whole budget was 250+500ms — under three
;; seconds — so a customer's paid job was abandoned while the provider was
;; still only briefly busy.
;;
;; A translation job is BATCH work: nobody is watching a socket, and the job
;; already carries a hold. Waiting ~30s beats failing, so the budget is
;; 4 retries at 1s base -> 1+2+4+8s.
(def ^:private default-post-opts
  {:max-retries 4
   :base-delay-ms 1000
   :throttle-ms 250
   ;; Cap on a provider-supplied Retry-After, so a hostile or absurd value
   ;; cannot park a worker thread indefinitely.
   :max-retry-after-ms 30000})

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

(defn- header-value
  "One response header by lower-cased name, or nil. java.net.http headers are
   already case-insensitive; this only unwraps the Optional."
  [resp header]
  (when (instance? java.net.http.HttpResponse resp)
    (-> ^java.net.http.HttpResponse resp
        .headers
        (.firstValue header)
        (.orElse nil))))

(defn- retry-after-ms
  "The provider's own Retry-After for this response, in ms, or nil.

   A 429 usually carries one, and it is better information than any backoff we
   compute: it is the provider saying WHEN it will be ready. Only the
   delta-seconds form is honoured — the HTTP-date form needs a clock, and this
   namespace has none. Values are clamped to `cap-ms`."
  [resp cap-ms]
  (some-> (header-value resp "retry-after")
          parse-long
          (max 0)
          (* 1000)
          (min cap-ms)))

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

;; A pricing map states a NUMBER and never its unit, and the two dialects that
;; reach here disagree: OpenRouter quotes USD per TOKEN under :prompt
;; /:completion, Venice quotes USD per MILLION tokens under :input/:output.
;; Reading the second as the first is a 1e6 overcharge that still looks like a
;; plausible invoice, so each accepted key declares the token count its rate
;; covers instead of the unit being inferred from the map's shape.
(def ^:private per-token (bigdec 1))
(def ^:private per-million (bigdec 1000000))

(def ^:private input-rate-keys
  [[:prompt per-token]
   [:input per-million]
   [:prompt-usd-per-token per-token]
   [:input-usd-per-token per-token]])

(def ^:private output-rate-keys
  [[:completion per-token]
   [:output per-million]
   [:completion-usd-per-token per-token]
   [:output-usd-per-token per-token]])

(defn- token-price
  "USD for ONE token, or nil when `pricing` names no rate.

   `key-units` pairs each accepted key with the number of tokens that key's
   quoted rate covers, so a per-million quote is scaled down here and every
   caller may simply multiply by a token count."
  [pricing key-units]
  (some (fn [[k tokens-per-unit]]
          (let [value (get pricing k)
                rate (decimal (if (map? value) (:usd value) value))]
            ;; Exact: the divisor is a power of ten, so this never repeats.
            (some-> rate (/ tokens-per-unit))))
        key-units))

(defn- calculated-cost-micros [usage pricing]
  (let [input  (token-count usage :prompt_tokens :input_tokens)
        output (token-count usage :completion_tokens :output_tokens)
        in-rate (token-price pricing input-rate-keys)
        out-rate (token-price pricing output-rate-keys)]
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
  (let [{:keys [max-retries base-delay-ms throttle-ms on-attempt provider model
                pricing max-retry-after-ms]}
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
            ;; The provider's own Retry-After outranks our backoff curve: it
            ;; knows when it will be ready and we are guessing.
            (sleep! (or (retry-after-ms resp (or max-retry-after-ms 30000))
                        (retry-delay-ms base-delay-ms attempt)))
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
