(ns vtranslate.engine.main
  "Boundary entrypoint the babashka CLI shells out to (subprocess transport).
   Reads one EDN job spec (argv[0] or stdin), wires default ports, runs the
   pipeline, and prints an EDN Result to stdout. Exit 0 on ok, 1 on err."
  (:require [clojure.edn :as edn]
            [hive-dsl.result :as r]
            [vtranslate.engine.addons :as addons]
            [vtranslate.engine.providers.config :as cfg]
            [vtranslate.engine.api :as api]
            [vtranslate.engine.calc.translation :as c.tr]
            [vtranslate.engine.domain.ingestion :as ing]
            [vtranslate.engine.port.fetcher :as p.fetch]
            [vtranslate.engine.wiring :as wiring]
            [clojure.string :as str])
  (:gen-class))

(defn- read-spec [args]
  (edn/read-string (or (first args) (slurp *in*))))

(defn- transcription-spec?
  "True when a job spec selects the ASR-only ingress (:operation :transcribe)."
  [spec]
  (= :transcribe (:operation spec)))

(defn- validate-spec
  "Boundary smart-ctor: a job spec MUST carry a non-blank :job-id and :source,
   plus at least one target language — :target-language or a non-empty
   :target-languages — unless :operation is :transcribe (ASR-only ingress). A
   missing :job-id would otherwise silently produce anemic '-asset'/'-tx'/'-sub'
   ids downstream.
   => (r/ok spec) | (r/err :error/invalid-job-spec {:missing [...]})."
  [spec]
  (let [missing (cond-> (vec (for [k [:job-id :source]
                                   :when (str/blank? (str (get spec k)))]
                               k))
                  (and (not (transcription-spec? spec))
                       (empty? (c.tr/normalize-targets spec)))
                  (conj :target-language))]
    (if (seq missing)
      (r/err :error/invalid-job-spec {:missing missing})
      (r/ok spec))))

(def core-adapter-nses
  "The core provider adapters — each ns self-registers its provider defmethod(s)
   on require. Public so register-adapters! diagnostics can be exercised in tests."
  '[vtranslate.engine.collect.port
    vtranslate.engine.adapters.composer.hardsub
    vtranslate.engine.adapters.composer.softmux
    vtranslate.engine.adapters.composer.both
    vtranslate.engine.adapters.segmenter.stub
    vtranslate.engine.adapters.segmenter.silero-vad
    vtranslate.engine.adapters.translator.identity
    vtranslate.engine.adapters.translator.llm
    vtranslate.engine.adapters.source.file
    vtranslate.engine.adapters.codec.dispatch
    vtranslate.engine.adapters.transcriber.stub
    vtranslate.engine.adapters.transcriber.openai-compatible
    vtranslate.engine.adapters.transcriber.whisper-jni
    vtranslate.engine.adapters.transcriber.sherpa-onnx
    vtranslate.engine.adapters.transcriber.onnx-bytedeco
    vtranslate.engine.adapters.transcriber.whisper-ffm
    vtranslate.engine.adapters.transcriber.qwen3-asr
    vtranslate.engine.adapters.transcriber.nemo-python])

(defn- resolved-addons [config]
  (let [config (or config {})
        resolved (cfg/resolve-routing config)]
    (if (r/ok? resolved)
      (:addons (:ok resolved))
      (:addons config))))

(defn load-addons! [config]
  (addons/load-addons! (resolved-addons config)))

(defn register-adapters!
  "Require every core adapter ns (each self-registers its provider defmethods).
   A core adapter that can't load on this classpath (e.g. a missing native) is
   TOLERATED — the provider is simply unavailable and the resolver fails loud
   later — but the failure is RECORDED + logged to stderr, never silently
   swallowed. Then load configured addons. Returns {:failed [[ns message] ...]}."
  ([] (register-adapters! {}))
  ([config]
   (let [failed (into []
                      (keep (fn [adapter-ns]
                              (try (require adapter-ns) nil
                                   (catch Throwable t [adapter-ns (.getMessage t)]))))
                      core-adapter-nses)]
     (when (seq failed)
       (binding [*out* *err*]
         (println (str "[vtranslate] " (count failed)
                       " core adapter(s) unavailable on this classpath: "
                       (str/join ", " (map first failed))))))
     (load-addons! config)
     {:failed failed})))

(defn- ingress-kind
  "MediaKind for a spec: an explicit :asset-kind, else inferred from the source
   extension. Chooses which port set + api ingress the boundary dispatches to."
  [spec]
  (or (:asset-kind spec) (ing/infer-kind (:source spec))))

(defn localized-spec
  "Rewrite `spec` to point at the file a fetch produced, keeping the locator it
   came from. Pure — the fetch already happened."
  [spec {:keys [local-path title metadata]}]
  (cond-> (assoc spec
                 :source local-path
                 :source/origin (:source spec))
    title    (assoc :source/title title)
    metadata (assoc :source/metadata metadata)))

(defn- resolve-source
  "Resolve a remote :source to a file on disk before anything downstream sees it.
   Probe, demux and kind inference all take a path, so this runs ahead of
   `ingress-kind` — a URL never reaches extension sniffing. A local source passes
   through untouched, and with no fetch addon loaded a remote one fails loud.
   => (r/ok spec) | (r/err :error/no-source-fetcher|:error/fetch-* {...})."
  [spec]
  (if-not (ing/remote-source? (:source spec))
    (r/ok spec)
    (r/let-ok [fetcher (wiring/build-port :fetcher (:config spec))
               fetched (p.fetch/fetch-media fetcher
                                            (:source spec)
                                            (or (get-in spec [:config :fetcher-opts]) {}))]
      (r/ok (localized-spec spec fetched)))))

(defn run
  "Boundary: parse spec -> validate -> load adapters -> resolve a remote source
   -> wire ports -> run-job. Returns a Result. A top-level Throwable guard funnels
   EVERY escaping throwable into a structured (r/err ...) — native bytedeco Errors
   (mis-paired/missing natives), malformed-EDN parse failures, temp-file IO — so
   the subprocess ALWAYS prints a Result and exits cleanly, never dying with a raw
   stack trace."
  [args]
  (r/guard Throwable (r/err :error/uncaught {:phase :run})
    (r/let-ok [spec (r/ok (read-spec args))
               spec (validate-spec spec)]
        (let [config (:config spec)]
          (register-adapters! config)
          (r/let-ok [spec (resolve-source spec)]
          (cond
            (transcription-spec? spec)
            (r/let-ok [ports (wiring/transcription-ports config)] ; Ingress C — ASR only
              (api/run-transcription-job (assoc ports :config config) spec))

            (= :media/subtitle (ingress-kind spec))
            (r/let-ok [ports (wiring/parse-ports config)]   ; Ingress B — no ASR
              (api/run-subtitle-job ports spec))

            :else
            (r/let-ok [ports (wiring/default-ports config)] ; Ingress A — demux + ASR
              (api/run-job (assoc ports :config config) spec))))))))

(defn -main [& args]
  (let [result (run args)]
    (prn result)
    (System/exit (if (r/ok? result) 0 1))))