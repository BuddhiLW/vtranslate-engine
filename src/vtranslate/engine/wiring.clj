(ns vtranslate.engine.wiring
  "Compose (DIP seam, OCP). `default-ports` assembles the port map api/run-job
   needs. Real adapters arrive at M3 (whisper / translator / subtitle / JavaCV);
   until then the seam is explicit. `build-port` is an OPEN defmulti so a new
   provider is added by a new method, not by editing this ns (OCP)."
  (:require [hive-dsl.result :as r]
            [vtranslate.engine.providers.config :as cfg]
            [vtranslate.engine.providers.router :as router]
            [vtranslate.engine.providers.composer-registry :as cmp-reg]))

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
   :renderer :muxer} from `config`. :media/:segmenter come from the ffmpeg +
   grid-stub adapters (loaded only on the :ffmpeg classpath); :transcriber fails
   loud until a real ASR adapter lands. :muxer is nil unless config selects it
   ([:providers :composer])."
  [config]
  (r/let-ok [media        (build-port :media config)
             segmenter    (build-port :segmenter config)
             transcriber  (build-port :transcriber config)
             translator   (build-port :translator config)
             renderer     (build-port :renderer config)
             muxer        (build-port :composer config)]
    (r/ok {:media media :segmenter segmenter :transcriber transcriber
           :translator translator :renderer renderer :muxer muxer})))

(defmethod build-port :composer
  ;; L2 resolve-routing picks the composition strategy key; the L3 registry builds
  ;; it. :none (default) => no muxer, so the compose stage is a passthrough.
  [_ config]
  (r/let-ok [routing (cfg/resolve-routing config)]
    (let [composer (:composer routing)]
      (if (contains? #{nil :none} composer)
        (r/ok nil)
        (cmp-reg/resolve-composer composer (merge config routing))))))

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

(defn- decorate-translator
  "Wrap `translator` with the contextual decorator when config asks for it, else
   return it unchanged."
  [translator config]
  (if-let [wrap (requiring-resolve 'vtranslate.engine.adapters.translator.contextual/wrap)]
    (wrap translator config)
    translator))

(defmethod build-port :translator
  [_ config]
  (r/let-ok [routing    (cfg/resolve-routing config)
             translator (router/resolve-active-translator routing (merge config routing))]
    (r/ok (decorate-translator translator (merge config routing)))))

(defmethod build-port :transcriber
  ;; L2 resolve-routing picks the provider key; L4 router builds it (fail-loud).
  [_ config]
  (r/let-ok [routing (cfg/resolve-routing config)]
    (router/resolve-active-transcriber routing (merge config routing))))