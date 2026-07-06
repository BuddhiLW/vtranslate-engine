(ns vtranslate.engine.adapters.segmenter.silero-vad-native
  "Native ONNX Runtime half for Silero VAD. Loaded only under :silero-vad alias."
  (:import [ai.onnxruntime OnnxTensor OrtEnvironment OrtSession OrtSession$SessionOptions]
           [java.util HashMap]))

(defn- reset-state [batch-size]
  {:state (make-array Float/TYPE 2 batch-size 128)
   :context nil
   :last-sr 0
   :last-batch-size 0})

(defn- session [model-path]
  (let [env  (OrtEnvironment/getEnvironment)
        opts (doto (OrtSession$SessionOptions.)
               (.setInterOpNumThreads 1)
               (.setIntraOpNumThreads 1)
               (.addCPU true))]
    (.createSession env model-path opts)))

(defn- chunk-with-context [state chunk sample-rate]
  (let [context-size (if (= sample-rate 16000) 64 32)
        num-samples  (if (= sample-rate 16000) 512 256)
        context      (or (:context state) (make-array Float/TYPE 1 context-size))
        x            (make-array Float/TYPE 1 (+ context-size num-samples))
        out-row      ^floats (aget ^"[[F" x 0)
        ctx-row      ^floats (aget ^"[[F" context 0)]
    (System/arraycopy ctx-row 0 out-row 0 context-size)
    (System/arraycopy ^floats chunk 0 out-row context-size num-samples)
    [x context-size]))

(defn- call-model [^OrtSession session state chunk sample-rate]
  (let [env              (OrtEnvironment/getEnvironment)
        [x context-size] (chunk-with-context state chunk sample-rate)
        input-tensor     (OnnxTensor/createTensor env x)
        state-tensor     (OnnxTensor/createTensor env (:state state))
        sr-tensor        (OnnxTensor/createTensor env (long-array [sample-rate]))
        inputs           (doto (HashMap.)
                           (.put "input" input-tensor)
                           (.put "sr" sr-tensor)
                           (.put "state" state-tensor))
        outputs          (.run session inputs)]
    (try
      (let [output       ^"[[F" (-> outputs (.get 0) (.getValue))
            next-state   ^"[[[F" (-> outputs (.get 1) (.getValue))
            next-context (make-array Float/TYPE 1 context-size)
            x-row        ^floats (aget ^"[[F" x 0)
            ctx-row      ^floats (aget ^"[[F" next-context 0)]
        (System/arraycopy x-row (- (alength x-row) context-size) ctx-row 0 context-size)
        [(double (aget ^floats (aget output 0) 0))
         (assoc state
                :state next-state
                :context next-context
                :last-sr sample-rate
                :last-batch-size 1)])
      (finally
        (.close outputs)
        (.close input-tensor)
        (.close state-tensor)
        (.close sr-tensor)))))

(defn speech-probs
  "Return vector of speech probabilities, one per Silero window."
  [model-path samples sample-rate]
  (when-not (contains? #{8000 16000} sample-rate)
    (throw (ex-info "Silero VAD supports only 8kHz/16kHz input"
                    {:sample-rate sample-rate})))
  (let [window-size (if (= sample-rate 16000) 512 256)]
    (with-open [s (session model-path)]
      (loop [start 0
             state (reset-state 1)
             probs []]
        (if (>= start (alength ^floats samples))
          probs
          (let [chunk     (float-array window-size)
                chunk-len (min window-size (- (alength ^floats samples) start))]
            (System/arraycopy ^floats samples start chunk 0 chunk-len)
            (let [[prob next-state] (call-model s state chunk sample-rate)]
              (recur (+ start window-size) next-state (conj probs prob)))))))))
