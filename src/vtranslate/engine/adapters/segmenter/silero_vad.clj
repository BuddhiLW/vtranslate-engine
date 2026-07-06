(ns vtranslate.engine.adapters.segmenter.silero-vad
  "ISegmenter over Silero VAD. Outer ns stays core-safe: no ONNX imports here.
   The native ONNX Runtime calls live in silero-vad-native and are reached only
   after :silero-vad alias + model-file gates pass."
  (:require [hive-dsl.result :as r]
            [vtranslate.engine.adapters.transcriber.support :as sup]
            [vtranslate.engine.port.segmenter :as p.seg]))

(def ^:private native-speech-probs-sym
  (quote vtranslate.engine.adapters.segmenter.silero-vad-native/speech-probs))

(def default-model-path
  "models/silero_vad.onnx")

(def default-threshold 0.5)
(def default-neg-threshold 0.35)
(def default-min-speech-ms 250)
(def default-min-silence-ms 100)
(def default-speech-pad-ms 30)

(defn- onnxruntime-present? []
  (try (Class/forName "ai.onnxruntime.OrtEnvironment") true
       (catch Throwable _ false)))

(defn- model-path-for [config]
  (or (get-in config [:segmenter-opts :model-path]) default-model-path))

(defn- model-present? [model-path]
  (boolean (and model-path (.exists (java.io.File. ^String model-path)))))

(defn- sample-rate-supported? [sample-rate]
  (contains? #{8000 16000} sample-rate))

(defn- window-size-samples [sample-rate]
  (case sample-rate
    16000 512
    8000 256))

(defn- samples->ms [samples sample-rate]
  (long (Math/round (* 1000.0 (/ (double samples) sample-rate)))))

(defn- pad-spans [raw-spans audio-length-samples speech-pad-samples]
  (let [n (count raw-spans)]
    (loop [i 0 spans (vec raw-spans)]
      (if (>= i n)
        spans
        (let [spans  (if (zero? i)
                       (update-in spans [i :start] #(max 0 (- % speech-pad-samples)))
                       spans)
              speech (nth spans i)]
          (recur
           (inc i)
           (if (< i (dec n))
             (let [next-speech (nth spans (inc i))
                   silence (- (:start next-speech) (:end speech))]
               (if (< silence (* 2 speech-pad-samples))
                 (-> spans
                     (assoc-in [i :end] (+ (:end speech) (quot silence 2)))
                     (assoc-in [(inc i) :start]
                               (max 0 (- (:start next-speech) (quot silence 2)))))
                 (-> spans
                     (assoc-in [i :end]
                               (min audio-length-samples
                                    (+ (:end speech) speech-pad-samples)))
                     (assoc-in [(inc i) :start]
                               (max 0 (- (:start next-speech) speech-pad-samples))))))
             (assoc-in spans [i :end]
                       (min audio-length-samples
                            (+ (:end speech) speech-pad-samples))))))))))

(defn speech-spans-from-probs
  "Pure Silero post-processing over per-window speech probabilities.
   Returns ordered, non-overlapping `{:start-ms :end-ms}` spans."
  [speech-probs {:keys [sample-rate audio-length-samples threshold neg-threshold
                        min-speech-ms min-silence-ms speech-pad-ms]
                 :or   {threshold default-threshold
                        neg-threshold default-neg-threshold
                        min-speech-ms default-min-speech-ms
                        min-silence-ms default-min-silence-ms
                        speech-pad-ms default-speech-pad-ms}}]
  (if-not (and (sample-rate-supported? sample-rate) (nat-int? audio-length-samples))
    []
    (let [window-size         (window-size-samples sample-rate)
          min-speech-samples  (long (* sample-rate (/ min-speech-ms 1000.0)))
          min-silence-samples (long (* sample-rate (/ min-silence-ms 1000.0)))
          speech-pad-samples  (long (* sample-rate (/ speech-pad-ms 1000.0)))
          initial             {:triggered? false :temp-end 0 :current nil :spans []}
          last-state
          (reduce-kv
           (fn [{:keys [triggered? temp-end current spans] :as state} i speech-prob]
             (let [sample-pos (* window-size i)
                   temp-end   (if (and (>= speech-prob threshold) (not (zero? temp-end)))
                                0
                                temp-end)]
               (cond
                 (and (>= speech-prob threshold) (not triggered?))
                 (assoc state :triggered? true :temp-end 0
                        :current {:start sample-pos})

                 (and (< speech-prob neg-threshold) triggered?)
                 (let [temp-end (if (zero? temp-end) sample-pos temp-end)]
                   (if (< (- sample-pos temp-end) min-silence-samples)
                     (assoc state :temp-end temp-end)
                     (let [span (assoc current :end temp-end)]
                       (assoc state
                              :triggered? false
                              :temp-end 0
                              :current nil
                              :spans (cond-> spans
                                       (> (- (:end span) (:start span)) min-speech-samples)
                                       (conj span))))))

                 :else
                 (assoc state :temp-end temp-end))))
           initial
           (vec speech-probs))
          raw-spans (cond-> (:spans last-state)
                      (and (:current last-state)
                           (> (- audio-length-samples (get-in last-state [:current :start]))
                              min-speech-samples))
                      (conj (assoc (:current last-state) :end audio-length-samples)))]
      (->> (pad-spans raw-spans audio-length-samples speech-pad-samples)
           (mapv (fn [{:keys [start end]}]
                   {:start-ms (samples->ms start sample-rate)
                    :end-ms   (samples->ms end sample-rate)}))
           (filterv #(< (:start-ms %) (:end-ms %)))))))

(defrecord SileroVadSegmenter [model-path threshold neg-threshold
                               min-speech-ms min-silence-ms speech-pad-ms]
  p.seg/ISegmenter
  (segment [_ audio-source _opts]
    (if-let [path (sup/audio->path audio-source)]
      (r/let-ok [{:keys [samples sample-rate]} (sup/read-wav-mono-floats path)
                 probs (r/guard Throwable
                               (r/err :error/segmentation-failed
                                      {:reason "silero-vad ONNX inference failed"})
                         (r/ok
                          (if-not (sample-rate-supported? sample-rate)
                            (throw (ex-info "Silero VAD supports only 8kHz/16kHz WAV input"
                                            {:sample-rate sample-rate}))
                            (let [speech-probs @(requiring-resolve native-speech-probs-sym)]
                              (speech-probs model-path samples sample-rate)))))]
        (r/ok {:spans (speech-spans-from-probs
                       probs
                       {:sample-rate sample-rate
                        :audio-length-samples (alength ^floats samples)
                        :threshold threshold
                        :neg-threshold neg-threshold
                        :min-speech-ms min-speech-ms
                        :min-silence-ms min-silence-ms
                        :speech-pad-ms speech-pad-ms})}))
      (r/err :error/segmentation-failed {:reason "audio-source carries no path"}))))

(defn make-segmenter [config]
  (let [model-path (model-path-for config)
        opts       (:segmenter-opts config)]
    (cond
      (not (onnxruntime-present?))
      (r/err :error/segmentation-failed
             {:reason "add the :silero-vad deps alias so ai.onnxruntime is on the classpath"})

      (not (model-present? model-path))
      (r/err :error/segmentation-failed
             {:reason (str "no Silero VAD model at " model-path
                           " - download silero_vad.onnx or set config [:segmenter-opts :model-path]")})

      :else
      (r/ok (->SileroVadSegmenter
             model-path
             (float (or (:threshold opts) default-threshold))
             (float (or (:neg-threshold opts) default-neg-threshold))
             (long (or (:min-speech-ms opts) default-min-speech-ms))
             (long (or (:min-silence-ms opts) default-min-silence-ms))
             (long (or (:speech-pad-ms opts) default-speech-pad-ms)))))))
