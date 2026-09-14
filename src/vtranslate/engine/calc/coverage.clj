(ns vtranslate.engine.calc.coverage
  "Pure span coverage algebra — what a segmenter DID NOT cut, and how to cover it.

   A voice-activity segmenter decides, silently and alone, which audio ever
   reaches ASR: speech it rejects is not a worse transcript, it is no transcript
   at all, and the job still reports success. Measured on Sintel's closing three
   minutes, Silero accepted one span of 1.7 s out of 180 s and the run produced a
   single cue — the same clip tiled by the grid produced 37. So the spans a
   segmenter returns are treated here as the audio it is CONFIDENT about, and
   everything it left out is tiled anyway; whisper decides what is speech.")

(defn- clamp-spans
  "Ordered, non-overlapping, in-bounds spans. Touching spans stay separate —
   merging them would change what the caller's segmenter said."
  [spans duration-ms]
  (->> spans
       (keep (fn [{:keys [start-ms end-ms]}]
               (let [s (max 0 (min duration-ms (long (or start-ms 0))))
                     e (max s (min duration-ms (long (or end-ms 0))))]
                 (when (< s e) {:start-ms s :end-ms e}))))
       (sort-by (juxt :start-ms :end-ms))
       (reduce (fn [acc {:keys [start-ms end-ms] :as span}]
                 (let [prev (peek acc)]
                   (if (and prev (< start-ms (:end-ms prev)))
                     (conj (pop acc) (assoc prev :end-ms (max (:end-ms prev) end-ms)))
                     (conj acc span))))
               [])))

(defn gaps
  "The stretches of [0, `duration-ms`) that `spans` leave uncovered, each at
   least `min-gap-ms` long. => [{:start-ms n :end-ms n} ...] in time order."
  [spans duration-ms min-gap-ms]
  (if-not (pos? duration-ms)
    []
    (let [covered (clamp-spans spans duration-ms)
          edges   (concat (map :start-ms covered) [duration-ms])
          ends    (cons 0 (map :end-ms covered))]
      (into []
            (comp (map (fn [[s e]] {:start-ms s :end-ms e}))
                  (filter (fn [{:keys [start-ms end-ms]}]
                            (>= (- end-ms start-ms) min-gap-ms))))
            (map vector ends edges)))))

(defn tile
  "Tile one span into contiguous windows of at most `window-ms`, the last one
   clamped to the span's end. => [{:start-ms n :end-ms n} ...]."
  [{:keys [start-ms end-ms]} window-ms]
  (if-not (pos? window-ms)
    [{:start-ms start-ms :end-ms end-ms}]
    (loop [s start-ms acc []]
      (if (<= (- end-ms s) window-ms)
        (conj acc {:start-ms s :end-ms end-ms})
        (recur (+ s window-ms) (conj acc {:start-ms s :end-ms (+ s window-ms)}))))))

(defn fill-gaps
  "`spans` plus grid windows of `window-ms` over every gap of at least
   `min-gap-ms` in [0, `duration-ms`), so no audio is silently skipped. The
   segmenter's own spans are preserved exactly — they carry its utterance
   boundaries, which a grid does not — and only the space between them is
   tiled. Ordered by start, non-overlapping, in bounds.

   The 1-arity map form is the value-object entry point (a coverage request:
   {:spans :duration-ms :window-ms :min-gap-ms}); the 4-arity is the positional
   one the policy records call.
   => [{:start-ms n :end-ms n} ...]"
  ([{:keys [spans duration-ms window-ms min-gap-ms]}]
   (fill-gaps spans duration-ms window-ms min-gap-ms))
  ([spans duration-ms window-ms min-gap-ms]
   (if-not (pos? duration-ms)
     (vec spans)
     (let [kept (clamp-spans spans duration-ms)]
       (->> (gaps kept duration-ms min-gap-ms)
            (mapcat #(tile % window-ms))
            (into kept)
            (sort-by (juxt :start-ms :end-ms))
            vec)))))
