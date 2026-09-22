(ns vtranslate.engine.adapters.model-host.speaches
  "IModelHost over a speaches server's /api/ps: GET lists the resident models,
   POST /api/ps/<id> loads one, DELETE /api/ps/<id> unloads one (409 while a
   decode holds it)."
  (:require [aleph.http :as http]
            [clojure.string :as str]
            [cheshire.core :as json]
            [hive-dsl.result :as r]
            [vtranslate.engine.port.model-host :as p.host]))

(def ^:private timeouts
  "A load reads the weights and moves them to the device, so it may take a while."
  {:connection-timeout 15000 :request-timeout 300000})

(defn base-url
  "The server root of a speaches transcription endpoint `api-url`. => String"
  [api-url]
  (str/replace (str api-url) #"/v1/audio/transcriptions/?$|/+$" ""))

(defn- send!
  "`method` (:get :post :delete) on `url`, any status answered.
   => (r/ok {:status n :body s}) | (r/err :error/model-host ...)"
  [method url]
  (r/try-effect* :error/model-host
    (let [{:keys [status body]} @(http/request (merge timeouts
                                                      {:request-method   method
                                                       :url              url
                                                       :throw-exceptions false}))]
      {:status status :body (some-> body slurp)})))

(defn- unexpected [what model-id {:keys [status body]}]
  (r/err :error/model-host {:op what :model model-id :status status :body body}))

(defrecord SpeachesHost [base]
  p.host/IModelHost
  (loaded [_]
    (r/let-ok [{:keys [status body] :as resp} (send! :get (str base "/api/ps"))]
      (if (= 200 status)
        (r/ok (set (:models (json/parse-string body true))))
        (unexpected :loaded nil resp))))
  (load! [_ model-id]
    (r/let-ok [{:keys [status] :as resp} (send! :post (str base "/api/ps/" model-id))]
      (if (#{200 201 409} status)
        (r/ok model-id)
        (unexpected :load model-id resp))))
  (unload! [_ model-id]
    (r/let-ok [{:keys [status] :as resp} (send! :delete (str base "/api/ps/" model-id))]
      (case (long status)
        (200 204) (r/ok :unloaded)
        404       (r/ok :absent)
        409       (r/ok :busy)
        (unexpected :unload model-id resp)))))

(defn host
  "An IModelHost for the speaches server behind transcription endpoint `api-url`."
  [api-url]
  (->SpeachesHost (base-url api-url)))
