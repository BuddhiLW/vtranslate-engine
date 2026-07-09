(ns vtranslate.engine.addons.fake-iaddon
  (:require [hive-mcp.addons.protocol :as proto]))

(defrecord FakeAddon [state]
  proto/IAddon
  (initialize! [_ config]
    (swap! state assoc :initialized-with config)
    {:success? true
     :metadata {:config config}}))

(defn init-as-addon! [config]
  (->FakeAddon (atom {:created-with config})))
