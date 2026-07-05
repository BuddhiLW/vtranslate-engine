(ns vtranslate.engine.main
  "Boundary entrypoint the babashka CLI shells out to (subprocess transport).
   Reads one EDN job spec (argv[0] or stdin), wires default ports, runs the
   pipeline, and prints an EDN Result to stdout. Exit 0 on ok, 1 on err."
  (:require [clojure.edn :as edn]
            [hive-dsl.result :as r]
            [vtranslate.engine.api :as api]
            [vtranslate.engine.domain.ingestion :as ing]
            [vtranslate.engine.wiring :as wiring])
  (:gen-class))

(defn- read-spec [args]
  (edn/read-string (or (first args) (slurp *in*))))

(defn register-adapters!
  "Best-effort require of adapter nses so their wiring/build-port + provider
   registry defmethods register (OCP plugin discovery). collect.port pulls bytedeco
   and resolves only under the :ffmpeg alias; a missing optional dep is swallowed so
   the core stays loadable + runnable without it. The provider adapters (translator
   identity + LLM) have no native deps and load unconditionally. Call BEFORE
   wiring/default-ports."
  []
  (doseq [ns '[vtranslate.engine.collect.port
               vtranslate.engine.adapters.segmenter.stub
               vtranslate.engine.adapters.translator.identity
               vtranslate.engine.adapters.translator.llm
               vtranslate.engine.adapters.source.file
               vtranslate.engine.adapters.codec.dispatch]]
    (try (require ns) (catch Throwable _ nil))))

(defn- ingress-kind
  "MediaKind for a spec: an explicit :asset-kind, else inferred from the source
   extension. Chooses which port set + api ingress the boundary dispatches to."
  [spec]
  (or (:asset-kind spec) (ing/infer-kind (:source spec))))

(defn run
  "Boundary: load adapters -> parse spec -> wire ports -> run-job. Returns a
   Result. A top-level Throwable guard funnels EVERY escaping throwable into a
   structured (r/err ...) — native bytedeco Errors (mis-paired/missing natives),
   malformed-EDN parse failures, temp-file IO — so the subprocess ALWAYS prints a
   Result and exits cleanly, never dying with a raw stack trace."
  [args]
  (r/guard Throwable (r/err :error/uncaught {:phase :run})
    (do
      (register-adapters!)
      (r/let-ok [spec (r/ok (read-spec args))]
        (let [config (:config spec)]
          (if (= :media/subtitle (ingress-kind spec))
            (r/let-ok [ports (wiring/parse-ports config)]   ; Ingress B — no ASR
              (api/run-subtitle-job ports spec))
            (r/let-ok [ports (wiring/default-ports config)] ; Ingress A — demux + ASR
              (api/run-job ports spec))))))))

(defn -main [& args]
  (let [result (run args)]
    (prn result)
    (System/exit (if (r/ok? result) 0 1))))