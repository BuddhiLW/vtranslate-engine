(ns vad-probe
  "Isolate the Silero VAD ONNX crash: report the model's declared input/output
   signature, then run one 512-sample window through it."
  (:require [vtranslate.engine.adapters.transcriber.support :as sup])
  (:import [ai.onnxruntime OrtEnvironment OrtSession$SessionOptions]))

(defn -main [& [model-path wav-path]]
  (let [env  (OrtEnvironment/getEnvironment)
        opts (doto (OrtSession$SessionOptions.)
               (.setInterOpNumThreads 1)
               (.setIntraOpNumThreads 1))
        s    (.createSession env ^String model-path opts)]
    (println "INPUTS:")
    (doseq [[k v] (.getInputInfo s)] (println " " k "=>" (str v)))
    (println "OUTPUTS:")
    (doseq [[k v] (.getOutputInfo s)] (println " " k "=>" (str v)))
    (.close s)
    (when wav-path
      (let [{:keys [samples sample-rate]} (:ok (sup/read-wav-mono-floats wav-path))]
        (println "wav samples" (alength ^floats samples) "rate" sample-rate)
        (println "one window prob:"
                 (first (@(requiring-resolve
                           'vtranslate.engine.adapters.segmenter.silero-vad-native/speech-probs)
                         model-path (float-array (take 512 samples)) sample-rate)))))
    (shutdown-agents)))
