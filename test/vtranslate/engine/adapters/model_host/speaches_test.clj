(ns vtranslate.engine.adapters.model-host.speaches-test
  (:require [clojure.test :refer [deftest is]]
            [cheshire.core :as json]
            [vtranslate.engine.adapters.model-host.speaches :as sp]
            [vtranslate.engine.port.model-host :as p.host]
            [aleph.http :as http]
            [aleph.netty :as netty]))

(defn- ps-handler
  "A ring handler answering /api/ps the way speaches 0.8.3 does over the
   resident set `loaded` (an atom), where a model in `busy` refuses to unload,
   recording [method uri] in `seen`."
  [loaded busy seen]
  (fn [{:keys [request-method uri]}]
    (swap! seen conj [request-method uri])
    (let [id (subs uri (min (count uri) (count "/api/ps/")))]
      (case request-method
        :get    {:status 200 :body (json/generate-string {:models (sort @loaded)})}
        :post   (if (contains? @loaded id)
                  {:status 409 :body "Model already loaded"}
                  (do (swap! loaded conj id) {:status 201}))
        :delete (cond (contains? busy id)          {:status 409 :body "still in use"}
                      (not (contains? @loaded id)) {:status 404 :body "Model not found"}
                      :else (do (swap! loaded disj id) {:status 204}))))))

(defn- with-ps-server
  "Run `f` with the transcription URL of a local aleph server over
   `ps-handler`, and the atom of requests it saw."
  [loaded busy f]
  (let [seen   (atom [])
        server (http/start-server (ps-handler loaded busy seen) {:port 0})]
    (try
      (f (str "http://127.0.0.1:" (netty/port server) "/v1/audio/transcriptions") seen)
      (finally (.close ^java.io.Closeable server)))))

(deftest the-server-root-is-read-off-the-transcription-endpoint
  (is (= "http://vtranslate-asr:8000"
         (sp/base-url "http://vtranslate-asr:8000/v1/audio/transcriptions")))
  (is (= "http://h:1" (sp/base-url "http://h:1/"))))

(deftest it-speaks-speaches-api-ps
  (let [loaded (atom #{"a/x"})]
    (with-ps-server loaded #{"busy/y"}
      (fn [url seen]
        (let [h (sp/host url)]
          (is (= #{"a/x"} (:ok (p.host/loaded h))))
          (is (= "org/m-ct2" (:ok (p.host/load! h "org/m-ct2"))))
          (is (= "org/m-ct2" (:ok (p.host/load! h "org/m-ct2"))) "a second load is not an error")
          (is (= :unloaded (:ok (p.host/unload! h "a/x"))))
          (is (= :absent (:ok (p.host/unload! h "a/x"))))
          (swap! loaded conj "busy/y")
          (is (= :busy (:ok (p.host/unload! h "busy/y"))))
          (is (= #{"org/m-ct2" "busy/y"} @loaded))
          (is (= [:post "/api/ps/org/m-ct2"] (second @seen)) "a model id's slash stays in the path"))))))

(deftest a-server-that-is-down-is-an-error-not-an-empty-set
  (is (= :error/model-host
         (:error (p.host/loaded (sp/host "http://127.0.0.1:9/v1/audio/transcriptions"))))))
