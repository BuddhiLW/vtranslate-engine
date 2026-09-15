(ns vtranslate.engine.adapters.burner.ffmpeg-nvenc
  "IHardsubBurner over the system ffmpeg encoding on an NVIDIA GPU: libass
   still renders the captions on the CPU, h264_nvenc encodes. Self-registers
   (defmethod resolve-burner :ffmpeg-nvenc). Loads without bytedeco.

   Same `opts` as the CPU burner (:ffmpeg-bin, :process-runner), plus
   `:nvenc-preset` (default p4).

   A burn whose encoder cannot be OPENED (no device in the pod, a driver
   mismatch, every encode session taken) is re-run once on libx264, and the
   fallback is recorded on the burner's `fallbacks` counter. A job is not
   failed because the GPU was busy; a GPU that never works shows up as a
   counter that tracks the burn count. Any other failure is the media's and
   is rethrown untouched."
  (:require [hive-dsl.result :as r]
            [vtranslate.engine.adapters.burner.ffmpeg-cli :as cpu]
            [vtranslate.engine.calc.ffmpeg-args :as args]
            [vtranslate.engine.calc.overlay :as overlay]
            [vtranslate.engine.collect.ffmpeg-cli :as cli]
            [vtranslate.engine.port.burner :as p.burner]
            [vtranslate.engine.providers.burner-registry :as reg]))

(defn- hardware-open-failure?
  [e]
  (args/hardware-unavailable? (:stderr (ex-data e))))

(defrecord FfmpegNvencBurner [cli fallbacks]
  p.burner/IHardsubBurner
  (burn! [_ video-source out-path track style]
    (let [cues (overlay/timeline track)]
      (try
        (cli/burn-hardsub cli video-source out-path cues style :h264-nvenc)
        (catch clojure.lang.ExceptionInfo e
          (if (hardware-open-failure? e)
            (do (swap! fallbacks inc)
                (cli/burn-hardsub cli video-source out-path cues style :libx264))
            (throw e)))))))

(defn make-burner
  "Build an FfmpegNvencBurner from `opts`."
  [opts]
  (->FfmpegNvencBurner (cpu/executables opts) (atom 0)))

(defmethod reg/resolve-burner :ffmpeg-nvenc [_ opts]
  (r/ok (make-burner opts)))
