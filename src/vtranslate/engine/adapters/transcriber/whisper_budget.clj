(ns vtranslate.engine.adapters.transcriber.whisper-budget
  "PURE: which resident whisper.cpp contexts must be given back, for the next
   set of weights to fit on the device.

   Why this exists. whisper.cpp does not fail politely when a model does not
   fit: `whisper_init_from_file_with_params` walks into a null ggml buffer and
   the process dies inside libggml-base, taking the JVM with it. Measured
   2026-09-21 on an 8 GB RTX 4070 Laptop, twice (hs_err_pid638460,
   hs_err_pid696841): ivrit turbo f16 loaded beside a resident large-v3 q5_0.
   There is no Result to return from that, and no exception to catch, so the
   only defence is to refuse to call `init` at all.

   That makes admission a DECISION taken before the boundary, over numbers the
   boundary collected. This namespace is that decision and nothing else: no
   nvidia-smi, no file stat, no cache, no natives. It is the only part of the
   VRAM story that can be tested on a machine with no GPU.

   The state the boundary hands in:

     {:budget-bytes n        what the device lets us hold, in total
      :resident     {k n}    contexts already loaded, and what each is holding
      :pinned       #{k}     contexts with a decode in flight, never evictable
      :lru          [k ...]  least recently used FIRST, the eviction order}

   A key `k` is whatever the cache keys by; this namespace never looks inside
   one."
  (:require [clojure.string :as str]))

(def compute-margin-bytes
  "Headroom to assume per context, on top of the weights file, when deciding
   whether the next model may be loaded.

   A whisper context is not only its weights: the KV cache, the encoder and
   decoder compute buffers and the scratch allocations land on the same device
   and scale with the model's shape rather than with the audio. Measured on the
   8 GB RTX 4070 Laptop against the CUDA 1.7.5 build, by reading nvidia-smi
   either side of the load:

     ggml-large-v3-q5_0     file 1031 MiB -> 1831 MiB resident   (+800)
     ggml-ivrit-turbo f16   file 1549 MiB -> 2069 MiB resident   (+520)

   So the overhead is NOT a fixed fraction of the file: a quantized model has
   small weights and full-size compute buffers, which is why q5_0 costs more
   headroom than the larger f16 turbo. 896 MiB clears the worse of the two.

   This number only has to be safe, not tight. It decides ADMISSION, and the
   moment a context is loaded the ledger is corrected with what the device
   actually gave up, so an over-estimate costs one conservative refusal rather
   than a permanently wrong account. An under-estimate costs the JVM."
  (* 896 1024 1024))

(defn cost
  "Device bytes a model whose weights file is `file-bytes` is expected to hold
   once it is a live context. => long"
  [file-bytes]
  (+ (long (or file-bytes 0)) compute-margin-bytes))

(defn held
  "Bytes the `resident` contexts are holding between them. => long"
  [resident]
  (reduce + 0 (map long (vals resident))))

(defn- evictable
  "The keys of `lru`, in eviction order, that may actually be taken back: a
   context with a decode in flight is not one of them, and neither is a key the
   caller is asking for."
  [{:keys [lru pinned resident]} want-key]
  (remove (fn [k]
            (or (= k want-key)
                (contains? (set pinned) k)
                (not (contains? resident k))))
          lru))

(defn plan
  "What must happen for `want-key`, costing `want-bytes`, to be admitted to
   `state`.

   Evicts least-recently-used first, and only as far as it must: the aim is to
   admit the new weights, not to empty the device. A context that is mid-decode
   is never chosen, which is what lets eviction run while other jobs decode.

   => {:admit?   true/false
       :resident? true when the key is already loaded and nothing need happen
       :evict    [k ...]  in the order they should be freed
       :shortfall n       bytes still missing when :admit? is false}"
  [{:keys [budget-bytes resident] :as state} want-key want-bytes]
  (let [budget (long (or budget-bytes 0))
        want   (long (or want-bytes 0))]
    (cond
      ;; Already loaded. Nothing is admitted and nothing is given back; the
      ;; caller simply uses it, and the boundary marks it most recently used.
      (contains? resident want-key)
      {:admit? true :resident? true :evict [] :shortfall 0}

      ;; No budget means no ceiling: a CPU context is bounded by host RAM, and
      ;; a device we could not measure is one we must not pretend to know. The
      ;; old never-evict behaviour, preserved exactly.
      (not (pos? budget))
      {:admit? true :resident? false :evict [] :shortfall 0}

      ;; The weights cannot fit an EMPTY device. No amount of eviction helps,
      ;; and saying so names the real problem rather than blaming the last row.
      (> want budget)
      {:admit? false :resident? false :evict [] :shortfall (- want budget)
       :reason :budget/model-exceeds-device}

      :else
      (let [free (- budget (held resident))]
        (if (<= want free)
          {:admit? true :resident? false :evict [] :shortfall 0}
          (loop [[k & more] (evictable state want-key)
                 freed 0
                 out   []]
            (cond
              (<= want (+ free freed))
              {:admit? true :resident? false :evict out :shortfall 0}

              (nil? k)
              {:admit? false :resident? false :evict []
               :shortfall (- want (+ free freed))
               :reason :budget/pinned-contexts-hold-the-device}

              :else
              (recur more
                     (+ freed (long (get resident k 0)))
                     (conj out k)))))))))

(defn touch
  "`lru` with `k` moved to the most recently used end. => vector"
  [lru k]
  (conj (vec (remove #{k} lru)) k))

(defn forget
  "`state` with every key in `ks` no longer resident. The boundary calls this
   once the contexts really have been freed, never before."
  [state ks]
  (let [gone (set ks)]
    (-> state
        (update :resident #(apply dissoc % gone))
        (update :lru #(vec (remove gone %))))))

(defn admit
  "`state` with `k` resident at `bytes` and most recently used."
  [state k bytes]
  (-> state
      (assoc-in [:resident k] (long bytes))
      (update :lru touch k)))

(defn explain
  "One line saying why `want-key` could not be admitted, for the ex-info that
   replaces the crash. => String"
  [{:keys [budget-bytes resident pinned]} want-key want-bytes {:keys [shortfall reason]}]
  (let [mb #(long (/ (long (or % 0)) 1024 1024))]
    (str "whisper: refusing to load " want-key " (" (mb want-bytes) " MiB needed"
         ", " (mb shortfall) " MiB short of a " (mb budget-bytes) " MiB budget"
         ", " (mb (held resident)) " MiB resident across " (count resident) " context(s)"
         (when (seq pinned) (str ", " (count pinned) " decoding"))
         "). "
         (case reason
           :budget/model-exceeds-device
           "These weights do not fit this device even with nothing else loaded."
           :budget/pinned-contexts-hold-the-device
           (str "The contexts that would have been evicted are mid-decode: "
                (str/join ", " (sort (map str pinned))) ".")
           "")
         " Loading it anyway would abort the JVM inside libggml rather than fail.")))
