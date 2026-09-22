(ns vtranslate.engine.adapters.burner.ffmpeg-nvenc
  "The NVIDIA burner, now an ALIAS over the config-driven hardware burner in
   adapters.burner.ffmpeg-hw. Nothing in the old implementation was
   NVIDIA-specific except the literal :h264-nvenc encoder key, so the record,
   the fallback-to-libx264 path and the counter all moved there and this
   namespace keeps only the name.

   It exists because the substitution must be INVISIBLE: a deployment naming
   :ffmpeg-nvenc, a caller requiring this ns, and every existing test of it
   must behave exactly as before. Requiring this ns registers :ffmpeg-nvenc,
   as it always did, and `make-burner` builds the same burner with the same
   :fallbacks atom on it."
  (:require [vtranslate.engine.adapters.burner.ffmpeg-hw :as hw]))

(defn make-burner
  "An h264_nvenc burner from `opts`. The NVENC spelling of
   `ffmpeg-hw/make-burner`."
  [opts]
  (hw/make-burner opts :h264-nvenc))

;; The :ffmpeg-nvenc defmethod lives in ffmpeg-hw, beside :ffmpeg-vaapi:
;; one registration for one implementation. Requiring this ns pulls it.
