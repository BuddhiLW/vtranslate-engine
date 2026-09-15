(ns vad-probe
  "Run the shipped Silero VAD over a mono 16k wav: per-second speech probability
   and the spans the segmenter would hand the ASR.
   Usage: clojure -M:dev:silero-vad -m vad-probe <file.wav>"
  (:require [vtranslate.engine.adapters.segmenter.silero-vad-native :as native]
            [vtranslate.engine.adapters.segmenter.silero-vad :as vad])
  (:import [javax.sound.sampled AudioSystem]
           [java.io File]))

(defn- read-wav
  "Mono 16k wav -> float array in [-1,1]."
  [path]
  (with-open [in (AudioSystem/getAudioInputStream (File. ^String path))]
    (let [fmt (.getFormat in)
          bytes (.readAllBytes in)
          n (quot (alength bytes) 2)
          out (float-array n)]
      (dotimes [i n]
        (let [lo (bit-and (aget bytes (* 2 i)) 0xff)
              hi (aget bytes (inc (* 2 i)))
              v (short (bit-or (bit-shift-left hi 8) lo))]
          (aset out i (float (/ v 32768.0)))))
      {:samples out :sample-rate (long (.getSampleRate fmt))})))

(defn -main [& [wav]]
  (let [{:keys [samples sample-rate]} (read-wav wav)
        probs (native/speech-probs "models/silero_vad.onnx" samples sample-rate)
        ;; Silero's window at 16k is 512 samples = 32 ms.
        per-win-ms (/ (* 1000.0 (/ (count samples) (double sample-rate))) (double (count probs)))
        by-sec (group-by #(long (quot (* % per-win-ms) 1000.0)) (range (count probs)))]
    (println (format "samples=%d sr=%d windows=%d window-ms=%.1f"
                     (count samples) sample-rate (count probs) per-win-ms))
    (println "sec  maxP   meanP  verdict@0.5")
    (doseq [s (sort (keys by-sec))]
      (let [ws (by-sec s)
            ps (map #(nth probs %) ws)
            mx (apply max ps)
            mn (/ (reduce + ps) (double (count ps)))]
        (println (format "%3ds  max=%.3f mean=%.3f  %s%s" s mx mn
                         (if (>= mx 0.5) "SPEECH" "  -   ")
                         (cond (= s 9) "   <-- cue1 ends"
                               (= s 30) "   <-- cue2 starts"
                               :else "")))))
    (let [spans (vad/speech-spans-from-probs probs {:sample-rate sample-rate
                                                  :audio-length-samples (count samples)})]
      (println "\nSPANS the segmenter would hand the ASR:")
      (doseq [{:keys [start-ms end-ms]} spans]
        (println (format "  %7.2fs -> %7.2fs  (%.2fs)"
                         (/ start-ms 1000.0) (/ end-ms 1000.0)
                         (/ (- end-ms start-ms) 1000.0)))))))
