(ns vtranslate.engine.adapters.support.llm-chat-deadline-test
  "The LLM request deadline bounds the WHOLE exchange, headers and body. The
   end-to-end tests drive a real local HTTP server that answers 200 and then
   stalls the body; the await-response tests pin the deadline on a bare future.
   Expiry must surface as the HttpTimeoutException the retry path handles."
  (:require [clojure.test :refer [deftest is]]
            [hive-dsl.result :as r]
            [vtranslate.engine.adapters.support.llm-chat :as sut])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.io IOException)
           (java.net ConnectException InetSocketAddress)
           (java.net.http HttpTimeoutException)
           (java.time Duration)
           (java.util.concurrent CompletableFuture Executors TimeUnit)))

(def ^:private ok-json
  "{\"model\":\"m\",\"choices\":[{\"message\":{\"content\":\"ok\"}}]}")

(defn- keepalive-then-stall
  "200 plus headers at once, then one whitespace byte every 100 ms for
   `stall-ms`: what OpenRouter sends while its upstream model is stuck.
   Delivers :dropped to `seen` when the client hangs up first, :completed
   when the stall ran out."
  [calls seen stall-ms]
  (reify HttpHandler
    (handle [_ ex]
      (swap! calls inc)
      (try
        (.sendResponseHeaders ^HttpExchange ex 200 0)
        (let [out (.getResponseBody ^HttpExchange ex)
              until (+ (System/currentTimeMillis) stall-ms)]
          (loop []
            (when (< (System/currentTimeMillis) until)
              (.write out (int 32))
              (.flush out)
              (Thread/sleep 100)
              (recur))))
        (deliver seen :completed)
        (catch IOException _
          (deliver seen :dropped))
        (catch InterruptedException _
          (deliver seen :server-stopped))
        (finally
          (.close ^HttpExchange ex))))))

(defn- answer-at-once [calls]
  (reify HttpHandler
    (handle [_ ex]
      (swap! calls inc)
      (let [bytes (.getBytes ^String ok-json "UTF-8")]
        (.sendResponseHeaders ^HttpExchange ex 200 (alength bytes))
        (with-open [out (.getResponseBody ^HttpExchange ex)]
          (.write out bytes))))))

(defn- with-server
  "Runs (f url) against a local HttpServer serving `handler` at /chat."
  [handler f]
  (let [srv (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        pool (Executors/newCachedThreadPool)]
    (.createContext srv "/chat" handler)
    (.setExecutor srv pool)
    (.start srv)
    (try
      (f (str "http://127.0.0.1:" (.getPort (.getAddress srv)) "/chat"))
      (finally
        (.stop srv 0)
        (.shutdownNow pool)
        (.awaitTermination pool 5 TimeUnit/SECONDS)))))

(defn- elapsed-ms [t0]
  (- (System/currentTimeMillis) t0))

(deftest a-fast-response-still-succeeds
  (let [calls (atom 0)]
    (with-server (answer-at-once calls)
      (fn [url]
        (is (= (r/ok "ok")
               (sut/post-chat :error/translation-failed url "key" "{}"
                              {:throttle-ms 0 :request-timeout-ms 5000})))
        (is (= 1 @calls))))))

(deftest a-stalled-body-is-cut-off-within-the-bound
  ;; HttpRequest.timeout is specified to guard only the wait for headers, and
  ;; here the headers arrive at once; the bound must still hold while the
  ;; body trickles keepalive bytes.
  (let [calls (atom 0)
        seen (promise)
        attempts (atom [])]
    (with-server (keepalive-then-stall calls seen 10000)
      (fn [url]
        (let [t0 (System/currentTimeMillis)
              res (sut/post-chat :error/translation-failed url "key" "{}"
                                 {:throttle-ms 0
                                  :request-timeout-ms 1000
                                  :max-timeout-retries 0
                                  :on-attempt #(swap! attempts conj %)})
              took (elapsed-ms t0)]
          (is (r/err? res))
          (is (= :error/translation-failed (:error res)))
          (is (= "class java.net.http.HttpTimeoutException" (:class res))
              "expiry maps onto the class the timeout-retry path catches")
          (is (<= 900 took 5000)
              (str "cut off near the 1 s bound, took " took " ms"))
          (is (= [:failed] (mapv :outcome @attempts)))
          (is (= :dropped (deref seen 5000 :still-streaming))
              "the cancelled exchange closes the connection, it does not leak"))))))

(deftest a-stalled-body-takes-the-existing-timeout-retry
  (let [calls (atom 0)
        attempts (atom [])]
    (with-server (keepalive-then-stall calls (promise) 10000)
      (fn [url]
        (let [t0 (System/currentTimeMillis)
              res (sut/post-chat :error/translation-failed url "key" "{}"
                                 {:throttle-ms 0
                                  :request-timeout-ms 1000
                                  :max-timeout-retries 1
                                  :on-attempt #(swap! attempts conj %)})
              took (elapsed-ms t0)]
          (is (r/err? res))
          (is (= 2 @calls) "the stall is retried once, then the batch fails")
          (is (= [:retryable-failure :failed] (mapv :outcome @attempts)))
          (is (= "class java.net.http.HttpTimeoutException"
                 (:error-class (first @attempts))))
          (is (< took 9000) (str "two bounded attempts, took " took " ms")))))))

(deftest the-request-timeout-bounds-send-directly
  (let [calls (atom 0)]
    (with-server (keepalive-then-stall calls (promise) 10000)
      (fn [url]
        (let [req (#'sut/chat-request url "key" "{}" 800)
              t0 (System/currentTimeMillis)
              thrown (try (#'sut/send-chat-request req) nil
                          (catch HttpTimeoutException e e))]
          (is (instance? HttpTimeoutException thrown))
          (is (< (elapsed-ms t0) 5000)))))))

;; The deadline itself, on a bare future: deterministic, no socket and no
;; dependence on how a given JDK's own header timer behaves.

(defn- thrown-by [f]
  (try (f) nil (catch Throwable t t)))

(deftest an-unanswered-future-expires-and-is-cancelled
  (let [fut (CompletableFuture.)
        t0 (System/currentTimeMillis)
        thrown (thrown-by #(#'sut/await-response fut (Duration/ofMillis 200)))]
    (is (instance? HttpTimeoutException thrown))
    (is (<= 150 (elapsed-ms t0) 3000))
    (is (.isCancelled fut) "expiry aborts the exchange rather than orphaning it")))

(deftest a-completed-future-is-returned-as-is
  (is (= :resp (#'sut/await-response (CompletableFuture/completedFuture :resp)
                                     (Duration/ofMillis 200))))
  (is (= :resp (#'sut/await-response (CompletableFuture/completedFuture :resp) nil))))

(deftest an-early-transport-failure-keeps-its-own-class
  ;; A refused connection is not a stall: it must not be retried as one.
  (let [cause (ConnectException. "refused")
        thrown (thrown-by #(#'sut/await-response
                            (CompletableFuture/failedFuture cause)
                            (Duration/ofSeconds 30)))]
    (is (identical? cause thrown))))

(deftest an-io-failure-at-the-deadline-is-reported-as-the-deadline
  ;; The JDK's own header timer races this deadline; when it wins it may end
  ;; the exchange with a bare IOException, which the retry path would not see.
  (let [cause (IOException. "exchange aborted")
        fut (CompletableFuture.)
        _ (future (Thread/sleep 300) (.completeExceptionally fut cause))
        thrown (thrown-by #(#'sut/await-response fut (Duration/ofMillis 250)))]
    (is (instance? HttpTimeoutException thrown))))

(deftest the-jdk-header-timeout-passes-through-unchanged
  (let [cause (HttpTimeoutException. "request timed out")
        thrown (thrown-by #(#'sut/await-response
                            (CompletableFuture/failedFuture cause)
                            (Duration/ofSeconds 30)))]
    (is (identical? cause thrown))))
