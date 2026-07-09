(ns vtranslate.engine.adapters.support.llm-chat-test
  (:require [clojure.test :refer [deftest is]]
            [hive-dsl.result :as r]
            [vtranslate.engine.adapters.support.llm-chat :as sut]))

(defn- success-body [content]
  (str "{\"choices\":[{\"message\":{\"content\":\"" content "\"}}]}"))

(deftest pass-secret-is-memoized
  (let [calls (atom 0)]
    (reset! (deref #'sut/pass-cache) {})
    (with-redefs-fn {#'sut/pass-show (fn [_path]
                                      (swap! calls inc)
                                      "secret")}
      #(do
         (is (= "secret" (sut/resolve-key "MISSING_ENV" "pass/path")))
         (is (= "secret" (sut/resolve-key "MISSING_ENV" "pass/path")))
         (is (= 1 @calls))))))

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
