;; Standalone Collect-layer smoke test — run with the :ffmpeg alias:
;;   clojure -M:ffmpeg -i dev/smoke_ffmpeg.clj
;; Exercises the REAL bytedeco JavaCV path end-to-end (probe + audio extract),
;; printing EDN results. Kept out of the nREPL channel because the JavaCPP
;; native loader floods stdout on first class-load.
(require '[vtranslate.engine.collect.ffmpeg :as ff]
         '[vtranslate.engine.collect.audio :as a]
         '[hive-dsl.result :as r])

(def backend (ff/make-backend))
(def files ["/home/leibniz/whatsapp_video.mp4"
            "/home/leibniz/clean.mp4"
            "/home/leibniz/whatsapp_video2.mp4"])
(def out-wav "/tmp/claude-1000/-home-leibniz-PP-vtranslate/924689be-36b7-4aa8-aa2a-b2df4bcb7aab/scratchpad/smoke-out.wav")

(defn line [k v] (println (format "RESULT %-14s %s" (name k) (pr-str v))))

(println "=== backend ===")
(line :backend-class (.getName (class backend)))

(println "=== probe (real ffmpeg) ===")
(doseq [f files]
  (let [res (a/probe backend f)]
    (line :probe (if (r/ok? res) (into {:file f} (:ok res)) {:file f :ERR res}))))

(println "=== extract-audio (real decode -> 16k mono WAV) ===")
(let [ex (a/extract-audio backend (first files) out-wav)]
  (line :extract (if (r/ok? ex) {:wrote (:ok ex)} {:ERR ex}))
  (when (r/ok? ex)
    (let [wf (java.io.File. out-wav)]
      (line :wav-bytes (.length wf))
      ;; re-probe the WAV we just wrote — proves the output is a valid container
      (let [rp (a/probe backend out-wav)]
        (line :reprobe-wav (if (r/ok? rp) (into {} (:ok rp)) {:ERR rp}))))))

(println "=== SMOKE DONE ===")
(shutdown-agents)
(System/exit 0)
