;; Standalone ASR runtime verification — real video -> SRT via :whisper-local
;; (whisper-jni, CPU natives from the jar). Run in an ISOLATED JVM (never the
;; shared nREPL; native JNI + JavaCPP loaders flood stdout on first class-load):
;;
;;   clojure -M:dev:ffmpeg:whisper-jni -i dev/asr_runtime_verify.clj
;;
;; Corpus media resolves via $VT_CORPUS (vtranslate.engine.dev). Config pins
;; :transcriber :whisper-local (models/ggml-large-v3.bin), :segmenter :none
;; (whisper produces native timestamps), :translator :identity (offline
;; passthrough — this verifies ASR, not MT). Writes the rendered SRT under
;; target/asr-runtime-verify/ and prints the first cues for spot-checking
;; against corpus/multisource/multisource.src.srt.
(require '[vtranslate.engine.dev :as dev]
         '[vtranslate.engine.main :as main]
         '[vtranslate.engine.wiring :as wiring]
         '[vtranslate.engine.api :as api]
         '[hive-dsl.result :as r]
         '[clojure.java.io :as io])

(def rel-source "multisource/multisource.mp4")
(def out-path "target/asr-runtime-verify/multisource.whisper-local.srt")

(def config
  {:segmenter   :none
   :transcriber :whisper-local
   :translator  :identity
   :composer    :none
   :transcriber-opts {:model-path "models/ggml-large-v3.bin"}})

(defn line [k v] (println (format "RESULT %-14s %s" (name k) (pr-str v))))

(let [source (dev/corpus-file rel-source)]
  (if-not source
    (line :skip (str "no corpus media at " rel-source " (set $VT_CORPUS)"))
    (do
      (line :source source)
      (let [{:keys [failed]} (main/register-adapters! config)]
        (line :adapters-failed failed))
      (let [result (r/let-ok [ports (wiring/default-ports config)]
                     (api/run-job (assoc ports :config config)
                                  {:job-id          "asr-verify"
                                   :source          source
                                   :source-language "auto"
                                   :target-language "en" ; identity passthrough; must differ from transcript lang ("und" for auto)
                                   :format          :format/srt}))]
        (if (r/err? result)
          (line :run-job-ERR result)
          (let [{:keys [rendered transcript]} (:ok result)
                cues (get-in transcript [:segments])]
            (io/make-parents out-path)
            (spit out-path rendered)
            (line :srt-path (.getCanonicalPath (io/file out-path)))
            (line :srt-bytes (count rendered))
            (line :cue-count (count cues))
            (println "=== first 10 cues (transcript segments) ===")
            (doseq [c (take 10 cues)] (prn c))
            (println "=== SRT head ===")
            (println (subs rendered 0 (min 1200 (count rendered))))))))))

(println "=== ASR RUNTIME VERIFY DONE ===")
(shutdown-agents)
(System/exit 0)
