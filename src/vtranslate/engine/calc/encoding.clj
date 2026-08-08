(ns vtranslate.engine.calc.encoding
  "Pure output-quality decisions for a re-encode. No IO.")

(def presets
  "Height in pixels each named quality targets. `:source` is not here: it means
   whatever arrived, which no table can know."
  {:2160p 2160
   :1440p 1440
   :1080p 1080
   :720p  720
   :480p  480
   :360p  360})

(defn- even-up
  "H.264 wants even dimensions."
  ^long [n]
  (let [n (long n)] (if (odd? n) (inc n) n)))

(defn target-dimensions
  "Output [width height] for `quality`, preserving the source aspect ratio.

   `:source` (the default) returns the frame as it arrived. A preset taller
   than the source is ignored: upscaling invents detail and costs encode time
   for a file nobody asked to be bigger.

   Both are rounded up to even numbers on every path, including `:source` —
   H.264 refuses odd dimensions, so passing an odd source through unchanged
   fails the encode rather than producing a smaller file."
  [width height quality]
  (let [width  (long width)
        height (long height)
        target (get presets quality)]
    (if (or (nil? target) (>= target height) (not (pos? height)))
      [(even-up width) (even-up height)]
      [(even-up (Math/round (* (double width) (/ (double target) height))))
       (even-up target)])))

(def ^:private bits-per-pixel-per-frame
  "Rate factor for H.264 at a visually-lossless-enough setting. Only used when
   the source does not report its own bitrate."
  0.10)

(def min-video-bitrate 400000)

(defn video-bitrate
  "Bits per second for the output.

   The source's own rate whenever it reports one, because a re-encode at the
   encoder's default silently downgrades every video that passes through.
   Scaled down with the pixel count when a smaller preset was asked for."
  ^long [{:keys [source-bitrate source-width source-height
                 width height frame-rate]}]
  (let [source-bitrate (long (or source-bitrate 0))
        width          (long (or width 0))
        height         (long (or height 0))
        frame-rate     (double (or frame-rate 30.0))
        source-pixels  (* (long (or source-width width))
                          (long (or source-height height)))
        pixels         (* width height)
        estimated      (long (* bits-per-pixel-per-frame pixels
                                (max 1.0 frame-rate)))
        base           (if (pos? source-bitrate) source-bitrate estimated)
        scaled         (if (and (pos? source-pixels) (pos? pixels)
                                (< pixels source-pixels))
                         (long (* base (/ (double pixels) source-pixels)))
                         base)]
    (max min-video-bitrate scaled)))

(defn audio-bitrate
  "Bits per second for the audio track. The source's own rate, else a rate that
   does not audibly cost anything at stereo."
  ^long [source-bitrate]
  (let [rate (long (or source-bitrate 0))]
    (if (pos? rate) rate 128000)))

(defn plan
  "Everything the recorder needs to return a video at the requested quality.
   => {:width :height :video-bitrate :audio-bitrate :rescaled?}"
  [{:keys [source-width source-height source-video-bitrate
           source-audio-bitrate frame-rate quality]
    :or   {quality :source}}]
  (let [[width height] (target-dimensions source-width source-height quality)]
    {:width width
     :height height
     :rescaled? (not= [width height] [(long source-width) (long source-height)])
     :video-bitrate (video-bitrate {:source-bitrate source-video-bitrate
                                    :source-width source-width
                                    :source-height source-height
                                    :width width
                                    :height height
                                    :frame-rate frame-rate})
     :audio-bitrate (audio-bitrate source-audio-bitrate)}))
