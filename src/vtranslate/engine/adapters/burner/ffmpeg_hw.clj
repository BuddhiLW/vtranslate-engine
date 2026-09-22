(ns vtranslate.engine.adapters.burner.ffmpeg-hw
  "IHardsubBurner over the system ffmpeg encoding on a HARDWARE encoder:
   libass still renders the captions on the CPU, the device encodes. Which
   device is CONFIG, not code: the burner carries an encoder key from
   calc.ffmpeg-args `encoders`, and a deployment may add a row for hardware
   this release has never heard of through :composer-opts :encoders.

   Self-registers two backend keys through one implementation:

     :ffmpeg-nvenc  => :h264-nvenc   (the historical key; an ALIAS now)
     :ffmpeg-vaapi  => :h264-vaapi

   Both are the same record with a different encoder, which is the point:
   nothing in the fallback logic was ever NVIDIA-specific except the literal
   key. :ffmpeg-nvenc keeps its exact prior behaviour, because a deployment
   naming it must not be able to tell the implementation was swapped.

   `opts` is the CPU burner's (:ffmpeg-bin, :process-runner), plus that
   encoder's own preset key (`:nvenc-preset`, default p4; h264_vaapi takes
   no preset at all) and optionally `:burn-encoder`, which names the encoder
   row directly and lets a config reach hardware with no registered alias.

   A burn whose encoder cannot be OPENED (no device in the pod, no render
   node, a driver mismatch, every encode session taken) is re-run once on
   libx264, and the fallback is recorded on the burner's `fallbacks`
   counter. A job is not failed because the GPU was busy; a GPU that never
   works shows up as a counter that tracks the burn count. Any other failure
   is the media's and is rethrown untouched."
  (:require [hive-dsl.result :as r]
            [vtranslate.engine.adapters.burner.ffmpeg-cli :as cpu]
            [vtranslate.engine.calc.ffmpeg-args :as args]
            [vtranslate.engine.calc.overlay :as overlay]
            [vtranslate.engine.collect.ffmpeg-cli :as cli]
            [vtranslate.engine.port.burner :as p.burner]
            [vtranslate.engine.providers.burner-registry :as reg]))

(def backend-encoders
  "The encoder each registered hardware BACKEND key means. A backend key
   names a deployment's intent (:ffmpeg-vaapi) and an encoder key names an
   ffmpeg row (:h264-vaapi); they are separate because one backend may later
   pick between rows, and because :ffmpeg-nvenc predates the split and must
   keep its spelling."
  {:ffmpeg-nvenc :h264-nvenc
   :ffmpeg-vaapi :h264-vaapi})

(defn- hardware-open-failure?
  [e]
  (args/hardware-unavailable? (:stderr (ex-data e))))

(defrecord FfmpegHwBurner [cli encoder fallbacks]
  p.burner/IHardsubBurner
  (burn! [_ video-source out-path track style]
    (let [cues (overlay/timeline track)]
      (try
        (cli/burn-hardsub cli video-source out-path cues style encoder)
        (catch clojure.lang.ExceptionInfo e
          (if (hardware-open-failure? e)
            (do (swap! fallbacks inc)
                (cli/burn-hardsub cli video-source out-path cues style :libx264))
            (throw e)))))))

(defn make-burner
  "Build an FfmpegHwBurner for `encoder` (a calc.ffmpeg-args `encoders` key)
   from `opts`. `:burn-encoder` in the opts wins, so a config can name a row
   this release does not map a backend key to. The encoder is resolved here,
   at wiring, so an unknown one fails before any job is accepted."
  ([opts] (make-burner opts :h264-nvenc))
  ([opts encoder]
   (let [k (get opts :burn-encoder encoder)]
     ;; Resolve for its throw: a burner built on an encoder no table knows
     ;; would otherwise fail on the first burn, per job, forever.
     (args/encoder-spec (args/encoders-from opts) k)
     (->FfmpegHwBurner (cpu/executables opts) k (atom 0)))))

(defn resolve-hw
  "(r/ok burner) for `backend-key`, or (r/err) when its encoder is unknown."
  [backend-key opts]
  (let [encoder (get backend-encoders backend-key)]
    (try
      (r/ok (make-burner opts encoder))
      (catch clojure.lang.ExceptionInfo e
        ;; r/err MERGES its data over {:error category}, so the data must
        ;; not carry an :error of its own or the category is overwritten.
        (r/err :error/unknown-encoder
               (assoc (dissoc (ex-data e) :error)
                      :backend backend-key
                      :message (ex-message e)))))))

(defmethod reg/resolve-burner :ffmpeg-nvenc [k opts] (resolve-hw k opts))
(defmethod reg/resolve-burner :ffmpeg-vaapi [k opts] (resolve-hw k opts))
