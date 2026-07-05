(ns vtranslate.engine.wiring
  "Compose (DIP seam, OCP). `default-ports` assembles the port map api/run-job
   needs. Real adapters arrive at M3 (whisper / translator / subtitle / JavaCV);
   until then the seam is explicit. `build-port` is an OPEN defmulti so a new
   provider is added by a new method, not by editing this ns (OCP)."
  (:require [hive-dsl.result :as r]
            [vtranslate.engine.providers.config :as cfg]
            [vtranslate.engine.providers.router :as router]))

(defmulti build-port
  "Build a port impl for a port key. Extension point: adapters register methods
   at M3. Dispatches on the port keyword."
  (fn [port-key _config] port-key))

(defmethod build-port :default
  [port-key _config]
  (r/err :error/adapters-not-wired
         {:port port-key :note "real adapters land at M3"}))

(defn default-ports
  "Assemble {:media :segmenter :transcriber :translator :renderer} from `config`.
   At M2 this returns an err until M3 adapters register build-port methods.
   :segmenter is served by the grid stub adapter (cutting phase A)."
  [config]
  (r/let-ok [media       (build-port :media config)
             segmenter   (build-port :segmenter config)
             transcriber (build-port :transcriber config)
             translator  (build-port :translator config)
             renderer    (build-port :renderer config)]
    (r/ok {:media media :segmenter segmenter :transcriber transcriber
           :translator translator :renderer renderer})))

(defmethod build-port :translator
  ;; Same seam; MT may degrade to the :identity passthrough terminus.
  [_ config]
  (r/let-ok [routing (cfg/resolve-routing config)]
    (router/resolve-active-translator routing config)))

(defmethod build-port :transcriber
  ;; L2 resolve-routing picks the provider key; L4 router builds it (fail-loud).
  [_ config]
  (r/let-ok [routing (cfg/resolve-routing config)]
    (router/resolve-active-transcriber routing config)))