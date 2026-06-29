;; Native ffmpeg WORKER — runs in a child JVM (clojure -M:ffmpeg). Probes the
;; media path in $VT_MEDIA and writes the result EDN to $VT_OUT. All native libav
;; / JavaCPP noise stays on THIS child's stdout/stderr (discarded by the caller),
;; so it never touches the parent nREPL channel. Driven from the main REPL via
;; hive-system shell/exec!. This is the out-of-process workaround for the fact
;; that native fd writes corrupt the in-process nREPL :out stream.
(require '[vtranslate.engine.collect.ffmpeg :as ff]
         '[vtranslate.engine.collect.audio :as a]
         '[hive-dsl.result :as r]
         '[clojure.string :as str])

(let [paths   (str/split (or (System/getenv "VT_MEDIA") "") #":")
      out     (System/getenv "VT_OUT")
      op      (or (System/getenv "VT_OP") "probe")
      backend (ff/make-backend)
      results (mapv (fn [p]
                      (case op
                        "probe"
                        (let [res (a/probe backend p)]
                          {:path p :ok? (r/ok? res)
                           :probe (when (r/ok? res) (into {} (:ok res)))
                           :error (when (r/err? res) (:error res))})
                        "extract"
                        (let [wav (str p ".16k.wav")
                              res (a/extract-audio backend p wav)]
                          {:path p :ok? (r/ok? res)
                           :wav (when (r/ok? res) (:ok res))
                           :bytes (when (r/ok? res) (.length (java.io.File. ^String (:ok res))))})))
                    paths)]
  (spit out (pr-str {:op op :results results}))
  (shutdown-agents)
  (System/exit 0))
