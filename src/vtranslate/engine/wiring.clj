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
  "Assemble the ASR ingress port set {:media :segmenter :transcriber :translator
   :renderer} from `config`. :media/:segmenter come from the ffmpeg + grid-stub
   adapters (loaded only on the :ffmpeg classpath); :transcriber fails loud until
   a real ASR adapter lands."
  [config]
  (r/let-ok [media       (build-port :media config)
             segmenter   (build-port :segmenter config)
             transcriber (build-port :transcriber config)
             translator  (build-port :translator config)
             renderer    (build-port :renderer config)]
    (r/ok {:media media :segmenter segmenter :transcriber transcriber
           :translator translator :renderer renderer})))

(defn parse-ports
  "Assemble the no-ASR (subtitle parse) ingress port set {:source :parser
   :translator :renderer} from `config`. The file-source + codec-dispatch adapters
   register :source / :subtitle-parser / :renderer; the translator resolves via
   the provider router (may degrade to the :identity passthrough). Unlike
   `default-ports`, every port here loads WITHOUT bytedeco — this is the first
   fully-runnable pipeline."
  [config]
  (r/let-ok [source     (build-port :source config)
             parser     (build-port :subtitle-parser config)
             translator (build-port :translator config)
             renderer   (build-port :renderer config)]
    (r/ok {:source source :parser parser :translator translator :renderer renderer})))

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