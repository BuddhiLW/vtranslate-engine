;; Standalone ASR runtime verification — real corpus audio -> transcript via
;; :qwen3-asr (pretrained-rstr, JVM-native Qwen3-ASR on the raster compiler).
;; Run in an ISOLATED JVM (never the shared nREPL):
;;
;;   clojure -J-Xmx8g -M:dev:ffmpeg:qwen3-asr -i dev/qwen3_asr_runtime_verify.clj
;;
;; First run auto-downloads sha-pinned weights (~1-2 GB) from HF into
;; ~/.cache/raster/models. Corpus media resolves via $VT_CORPUS
;; (vtranslate.engine.dev). Prints the transcript for spot-checking against
;; corpus/multisource/multisource.src.srt.
(require '[vtranslate.engine.dev :as dev]
         '[vtranslate.engine.main :as main]
         '[vtranslate.engine.providers.transcriber-registry :as reg]
         '[vtranslate.engine.port.transcriber :as p.asr]
         '[hive-dsl.result :as r])

(def rel-source "multisource/multisource.mp4")
(def config {:transcriber :qwen3-asr
             :transcriber-opts {:model-key :qwen3-asr-0.6b}})

(defn line [k v] (println (format "RESULT %-16s %s" (name k) (pr-str v))))

(let [source (dev/corpus-file rel-source)]
  (if-not source
    (line :skip (str "no corpus media at " rel-source " (set $VT_CORPUS)"))
    (do
      (line :source source)
      (let [{:keys [failed]} (main/register-adapters! config)]
        (line :adapters-failed failed))
      (r/let-ok [t   (reg/resolve-transcriber :qwen3-asr config)
                 wav (dev/extract! source)]
        (line :wav wav)
        (let [t0  (System/nanoTime)
              out (p.asr/transcribe t wav nil {})]
          (line :elapsed-s (double (/ (- (System/nanoTime) t0) 1e9)))
          (if (r/err? out)
            (line :transcribe-ERR out)
            (let [segs (:segments (:ok out))]
              (line :segment-count (count segs))
              (doseq [s segs] (line :segment s)))))))))

(println "=== QWEN3-ASR RUNTIME VERIFY DONE ===")
(shutdown-agents)
(System/exit 0)
