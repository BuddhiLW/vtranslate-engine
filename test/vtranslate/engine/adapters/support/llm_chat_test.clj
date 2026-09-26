(ns vtranslate.engine.adapters.support.llm-chat-test
  (:require [clojure.test :refer [deftest is]]
            [hive-dsl.result :as r]
            [vtranslate.engine.adapters.support.llm-chat :as sut]
            [cheshire.core])
  (:import (java.net.http HttpTimeoutException)))

(defn- success-body
  ([content] (success-body content nil))
  ([content usage]
   (str "{\"model\":\"model-1\",\"choices\":[{\"message\":{\"content\":\""
        content "\"}}]"
        (when usage (str ",\"usage\":" usage))
        "}")))

(deftest post-chat-retries-429
  (let [calls (atom 0)
        sleeps (atom [])]
    (with-redefs-fn {#'sut/send-chat-request (fn [_req]
                                              (if (= 1 (swap! calls inc))
                                                {:status 429 :body "rate limited"}
                                                {:status 200 :body (success-body "ok")}))
                      #'sut/sleep! (fn [ms] (swap! sleeps conj ms))
                      #'sut/throttle! (fn [_throttle-ms] nil)}
      #(let [res (sut/post-chat :error/translation-failed
                                "https://example.invalid/chat"
                                "key"
                                "{}"
                                {:max-retries 1
                                 :base-delay-ms 5
                                 :throttle-ms 0})]
         (is (r/ok? res))
         (is (= "ok" (:ok res)))
         (is (= 2 @calls))
         (is (= [5] @sleeps))))))

(deftest post-chat-surfaces-final-http-error
  (with-redefs-fn {#'sut/send-chat-request (constantly {:status 400 :body "bad request"})
                    #'sut/throttle! (fn [_throttle-ms] nil)}
    #(let [res (sut/post-chat :error/translation-failed
                              "https://example.invalid/chat"
                              "key"
                              "{}"
                              {:max-retries 0
                               :throttle-ms 0})]
       (is (r/err? res))
       (is (= :error/translation-failed (:error res)))
       (is (= 400 (:status res)))
       (is (= "bad request" (:body res))))))

(deftest post-chat-reports-every-provider-attempt
  (let [calls (atom 0)
        attempts (atom [])]
    (with-redefs-fn {#'sut/send-chat-request
                     (fn [_]
                       (if (= 1 (swap! calls inc))
                         {:status 503 :body "busy"}
                         {:status 200
                          :body (success-body
                                 "ok"
                                 "{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15,\"cost\":0.000123}")}))
                     #'sut/sleep! (fn [_] nil)
                     #'sut/throttle! (fn [_] nil)}
      #(let [res (sut/post-chat :error/translation-failed
                                "https://example.invalid/chat" "key" "{}"
                                {:max-retries 1
                                 :throttle-ms 0
                                 :provider :openrouter
                                 :model "requested-model"
                                 :on-attempt (fn [attempt]
                                               (swap! attempts conj attempt))})]
         (is (= (r/ok "ok") res))
         (is (= [:retryable-failure :succeeded] (mapv :outcome @attempts)))
         (is (= [1 2] (mapv :request-attempt @attempts)))
         (is (every? (fn [attempt]
                       (<= (:started-at attempt) (:finished-at attempt)))
                     @attempts))
         (is (= :openrouter (:provider (second @attempts))))
         (is (= "model-1" (:model (second @attempts))))
         (is (= {:input-tokens 10 :output-tokens 5 :total-tokens 15
                 :cost-micros 123}
                (:usage (second @attempts))))))))

(deftest post-chat-calculates-cost-when-provider-only-reports-tokens
  (let [attempt (atom nil)]
    (with-redefs-fn {#'sut/send-chat-request
                     (constantly
                      {:status 200
                       :body (success-body
                              "ok"
                              "{\"prompt_tokens\":100,\"completion_tokens\":50}")})
                     #'sut/throttle! (fn [_] nil)}
      #(do
         (sut/post-chat :error/translation-failed
                        "https://example.invalid/chat" "key" "{}"
                        {:throttle-ms 0
                         :pricing {:prompt "0.000001"
                                   :completion "0.000002"}
                         :on-attempt (fn [value] (reset! attempt value))})
         (is (= 200 (get-in @attempt [:usage :cost-micros])))
         (is (= 150 (get-in @attempt [:usage :total-tokens])))))))

(deftest pricing-dialects-do-not-share-a-unit
  ;; Both providers quote a bare number. OpenRouter's is USD per TOKEN,
  ;; Venice's is USD per MILLION tokens. Collapsing them onto one reading is a
  ;; 1e6 overcharge that still renders as a plausible invoice, so the two are
  ;; pinned here against the SAME token counts: whichever way a future edit
  ;; unifies them, one of these assertions has to move.
  (let [cost-micros #'sut/calculated-cost-micros
        usage {:prompt_tokens 1690 :completion_tokens 40}]
    ;; 1690 * 0.33/1e6 + 40 * 0.48/1e6 = $0.00057690 -> 577 micros.
    (is (= 577 (cost-micros usage {:input {:usd 0.33} :output {:usd 0.48}})))
    ;; 1690 * 0.000001 + 40 * 0.000002 = $0.00177 -> 1770 micros.
    (is (= 1770 (cost-micros usage {:prompt "0.000001" :completion "0.000002"})))
    ;; An explicitly-named per-token key is never scaled, whatever it sits
    ;; beside: 1690 * 0.33 + 40 * 0.48 = $576.90. That number is what the
    ;; Venice dialect used to bill, and it is only correct when the provider
    ;; really did quote per token.
    (is (= 576900000
           (cost-micros usage {:input-usd-per-token "0.33"
                               :output-usd-per-token "0.48"})))))

(deftest an-unpriced-call-is-costed-from-the-catalogue
  (let [attempt (atom nil)]
    (with-redefs-fn {#'sut/send-chat-request
                     (constantly
                      {:status 200
                       :body (success-body
                              "ok"
                              "{\"prompt_tokens\":1000000,\"completion_tokens\":1000000}")})
                     #'sut/throttle! (fn [_] nil)}
      #(do
         (sut/post-chat :error/review-failed
                        "https://example.invalid/chat" "key" "{}"
                        {:throttle-ms 0
                         :provider :venice
                         :model "kimi-k3"
                         :on-attempt (fn [value] (reset! attempt value))})
         (is (= 22500000 (get-in @attempt [:usage :cost-micros]))
             "3.75 + 18.75 USD for a million tokens each way")))))

(deftest an-unknown-model-still-records-no-cost
  (let [attempt (atom nil)]
    (with-redefs-fn {#'sut/send-chat-request
                     (constantly
                      {:status 200
                       :body (success-body "ok" "{\"prompt_tokens\":10,\"completion_tokens\":5}")})
                     #'sut/throttle! (fn [_] nil)}
      #(do
         (sut/post-chat :error/review-failed
                        "https://example.invalid/chat" "key" "{}"
                        {:throttle-ms 0 :provider :venice :model "no-such-model"
                         :on-attempt (fn [value] (reset! attempt value))})
         (is (nil? (get-in @attempt [:usage :cost-micros])))))))

(deftest body-params-are-merged-into-the-request-body
  (let [body (cheshire.core/parse-string
              (sut/chat-body-messages "m" [{:role "user" :content "x"}]
                                      {:body-params {:venice_parameters {:disable_thinking true}}})
              true)]
    (is (= {:disable_thinking true} (:venice_parameters body)))
    (is (= "m" (:model body)))
    (is (= 0.2 (:temperature body))))
  (is (not (contains? (cheshire.core/parse-string
                       (sut/chat-body-messages "m" [] {}) true)
                      :venice_parameters))))

(deftest the-retry-budget-outlasts-a-brief-provider-overload
  ;; Venice answers 429 "the model is currently overloaded" often enough that
  ;; on 2026-08-27 it killed about half a short run of real jobs. The budget is
  ;; a POLICY, not an incidental constant: a translation is batch work holding
  ;; a customer's money, so waiting tens of seconds beats abandoning it.
  ;;
  ;; Pinned so that shrinking it back towards the old sub-3-second budget has
  ;; to be a deliberate edit rather than a tidy-up.
  (let [{:keys [max-retries base-delay-ms max-retry-after-ms]}
        @#'sut/default-post-opts
        delay-for #'sut/retry-delay-ms
        total (reduce + (map #(delay-for base-delay-ms %) (range max-retries)))]
    (is (= 4 max-retries))
    (is (= 1000 base-delay-ms))
    (is (= 30000 max-retry-after-ms))
    ;; 1s + 2s + 4s + 8s: long enough that a provider blip is ridden out.
    (is (= 15000 total))
    (is (>= total 10000)
        "a budget under ten seconds abandons jobs the provider would have served")))

(defn- stalling-then-ok
  "A transport that times out `stalls` times, then answers 200."
  [calls stalls]
  (fn [_req]
    (if (<= (swap! calls inc) stalls)
      (throw (HttpTimeoutException. "request timed out"))
      {:status 200 :body (success-body "ok")})))

(deftest a-stalled-request-is-sent-once-more
  ;; Measured 2026-09-13 on job_29cde987: 2 of 12 Venice requests hit the
  ;; 300 s timeout at request-attempt 1 and were never retried, which failed
  ;; the whole job. A stall is as transient as a 429 and is retried like one.
  (let [calls (atom 0)
        attempts (atom [])]
    (with-redefs-fn {#'sut/send-chat-request (stalling-then-ok calls 1)
                     #'sut/throttle! (fn [_] nil)}
      #(let [res (sut/post-chat :error/translation-failed
                                "https://example.invalid/chat" "key" "{}"
                                {:throttle-ms 0
                                 :on-attempt (fn [a] (swap! attempts conj a))})]
         (is (= (r/ok "ok") res))
         (is (= 2 @calls))
         (is (= [:retryable-failure :succeeded] (mapv :outcome @attempts)))
         (is (= [1 2] (mapv :request-attempt @attempts))
             "the retry keeps counting requests")
         (is (= "class java.net.http.HttpTimeoutException"
                (:error-class (first @attempts))))))))

(deftest a-second-stall-fails-the-batch
  (let [calls (atom 0)
        attempts (atom [])]
    (with-redefs-fn {#'sut/send-chat-request (stalling-then-ok calls 99)
                     #'sut/throttle! (fn [_] nil)}
      #(let [res (sut/post-chat :error/translation-failed
                                "https://example.invalid/chat" "key" "{}"
                                {:throttle-ms 0
                                 :on-attempt (fn [a] (swap! attempts conj a))})]
         (is (r/err? res))
         (is (= :error/translation-failed (:error res)))
         (is (= "class java.net.http.HttpTimeoutException" (:class res)))
         (is (= 2 @calls) "one retry, not a loop")
         (is (= [:retryable-failure :failed] (mapv :outcome @attempts)))))))

(deftest timeout-retries-are-their-own-budget
  ;; A stall must not spend the 429 budget, and max-timeout-retries 0 restores
  ;; the old fail-on-first-stall behaviour for a caller that wants it.
  (let [calls (atom 0)]
    (with-redefs-fn {#'sut/send-chat-request (stalling-then-ok calls 1)
                     #'sut/throttle! (fn [_] nil)}
      #(let [res (sut/post-chat :error/translation-failed
                                "https://example.invalid/chat" "key" "{}"
                                {:throttle-ms 0 :max-timeout-retries 0})]
         (is (r/err? res))
         (is (= 1 @calls))))))
