(ns vtranslate.engine.calc.burn
  "Pure choice of the burn-in backend from :composer-opts. The adapter passes
   in what it observed (whether an ffmpeg binary answered); this decides.")

(def backends
  "`:ffmpeg-cli` runs the system ffmpeg with libass and libx264 in one
   process. `:ffmpeg-nvenc` is the same process encoding with h264_nvenc on
   an NVIDIA GPU, and re-runs a burn on libx264 when the GPU cannot open an
   encoder. `:javacv` draws each captioned frame in-process with Java2D and
   encodes with the bundled openh264, several times slower at 1080p but
   needing no binary. `:auto` takes the CLI when a binary answers.

   `:auto` never picks NVENC. A GPU is a scheduled, shared resource (the ASR
   holds one), so a deployment claims it by naming `:ffmpeg-nvenc` beside the
   device request, not by a worker discovering one."
  #{:ffmpeg-cli :ffmpeg-nvenc :javacv :auto})

(def default-binary "ffmpeg")

(defn binary
  "The ffmpeg executable to run: :ffmpeg-bin from the opts, else the bare
   name resolved from PATH."
  ^String [opts]
  (let [b (some-> (:ffmpeg-bin opts) str .trim)]
    (if (or (nil? b) (.isEmpty b)) default-binary b)))

(defn requested
  "The backend the opts ask for. Anything unknown, including nothing, reads
   as :auto, so a typo degrades to the safe choice rather than failing the
   job at compose time."
  [opts]
  (let [k (:burn-backend opts)]
    (if (contains? backends k) k :auto)))

(defn choose
  "=> :ffmpeg-cli | :ffmpeg-nvenc | :javacv. `available?` is whether the
   binary answered; it only matters under :auto. An explicit backend is
   honoured even when the binary did not answer, so a deployment that named
   it fails loud at the burn rather than silently taking the slow path."
  [opts available?]
  (case (requested opts)
    :ffmpeg-cli   :ffmpeg-cli
    :ffmpeg-nvenc :ffmpeg-nvenc
    :javacv       :javacv
    :auto         (if available? :ffmpeg-cli :javacv)))
