;; Standalone Collect-layer smoke test — run with the :dev + :ffmpeg aliases:
;;   clojure -M:dev:ffmpeg -i dev/smoke_ffmpeg.clj
;; Exercises the REAL bytedeco JavaCV path end-to-end (probe + audio extract)
;; over corpus media resolved via $VT_CORPUS (vtranslate.engine.dev), printing
;; EDN results. Kept out of the nREPL channel because the JavaCPP native
;; loader floods stdout on first class-load.
(require '[vtranslate.engine.dev :as dev]
         '[hive-dsl.result :as r])

(def rel-files ["bbb/clips/bbb_28-58s.mp4" "multisource/multisource.mp4"])
(def files (keep dev/corpus-file rel-files))

(defn line [k v] (println (format "RESULT %-14s %s" (name k) (pr-str v))))

(println "=== probe (real ffmpeg) ===")
(if (seq files)
  (doseq [f files]
    (let [res (dev/probe! f)]
      (line :probe (if (r/ok? res) (into {:file f} (:ok res)) {:file f :ERR res}))))
  (line :probe-skip "no corpus media found (set $VT_CORPUS)"))

(println "=== extract-audio (real decode -> 16k mono WAV) ===")
(when (seq files)
  (let [ex (dev/extract! (first files))]
    (line :extract (if (r/ok? ex) {:wrote (:ok ex)} {:ERR ex}))
    (when (r/ok? ex)
      (let [wf (java.io.File. ^String (:ok ex))]
        (line :wav-bytes (.length wf))
        ;; re-probe the WAV we just wrote — proves the output is a valid container
        (let [rp (dev/probe! (:ok ex))]
          (line :reprobe-wav (if (r/ok? rp) (into {} (:ok rp)) {:ERR rp})))))))

(println "=== SMOKE DONE ===")
(shutdown-agents)
(System/exit 0)
