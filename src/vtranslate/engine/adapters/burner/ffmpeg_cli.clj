(ns vtranslate.engine.adapters.burner.ffmpeg-cli
  "IHardsubBurner over the system ffmpeg: libass renders the captions from
   an ASS script, libx264 encodes, one process per burn. Self-registers
   (defmethod resolve-burner :ffmpeg-cli). Loads without bytedeco.

   `opts` (the composer's :composer-opts) may name the executable with
   `:ffmpeg-bin` and a process runner with `:process-runner`; the latter is
   how a test hands in a fake instead of the host's processes."
  (:require [hive-dsl.result :as r]
            [vtranslate.engine.calc.burn :as burn]
            [vtranslate.engine.calc.overlay :as overlay]
            [vtranslate.engine.collect.ffmpeg-cli :as cli]
            [vtranslate.engine.collect.process :as process]
            [vtranslate.engine.port.burner :as p.burner]
            [vtranslate.engine.providers.burner-registry :as reg]))

(defrecord FfmpegCliBurner [cli]
  p.burner/IHardsubBurner
  (burn! [_ video-source out-path track style]
    ;; The CLI wraps text itself from the same :wrap, so it takes the plain
    ;; timeline rather than a per-frame closure.
    (cli/burn-hardsub cli video-source out-path (overlay/timeline track) style)))

(defn executables
  "The ffmpeg CLI value `opts` describe: `:ffmpeg-bin` (default PATH's
   ffmpeg) under `:process-runner` (default the host's processes)."
  [opts]
  (cli/ffmpeg-cli (or (:process-runner opts) process/system-runner)
                  (burn/binary opts)))

(defn make-burner
  "Build an FfmpegCliBurner from `opts`."
  [opts]
  (->FfmpegCliBurner (executables opts)))

(defmethod reg/resolve-burner :ffmpeg-cli [_ opts]
  (r/ok (make-burner opts)))
