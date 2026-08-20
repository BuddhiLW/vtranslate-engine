(ns vtranslate.engine.adapters.support.llm-chat-test
  (:require [clojure.test :refer [deftest is]]
            [hive-dsl.result :as r]
            [vtranslate.engine.adapters.support.llm-chat :as sut]))

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
