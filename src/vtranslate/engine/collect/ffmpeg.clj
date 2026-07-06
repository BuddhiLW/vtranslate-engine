(ns vtranslate.engine.collect.ffmpeg
  "JavaCV (bytedeco) adapter — the ONLY namespace in the engine that imports a
   bytedeco type. In-process ffmpeg via the bundled native libraries; NO system
   ffmpeg binary is shelled out to.

   Two layers of the bytedeco stack are in play:
   - org.bytedeco/javacv      — FFmpegFrameGrabber / FFmpegFrameRecorder, the
                                high-level frame API used here by default.
   - org.bytedeco/ffmpeg      — javacpp-presets raw avcodec/avutil globals, used
                                only for stream-level constants (PCM codec id).

   HAZARDS encoded below (from the M5 decision 20260629141040-4ed00044):
   - The grabber/recorder are STATEFUL and NOT thread-safe. One instance per
     call, never shared across threads — `with-open` closes (stop + release of
     native memory) even on throw. Parallelism is achieved by the orchestration
     layer running MANY of these, each on its own thread, under a hard bound.
   - ffmpeg's time base is MICROSECONDS. Every duration is converted to integer
     milliseconds HERE, at the Collect boundary, so no µs leaks into the domain.
   - Loaded only with the :ffmpeg deps alias. Requiring it without that alias on
     the classpath throws at import — by design, the boundary requires it lazily."
  (:require [vtranslate.engine.collect.protocols :as p]
            [vtranslate.engine.collect.units :as units])
  (:import [org.bytedeco.javacv FFmpegFrameGrabber FFmpegFrameRecorder Frame
            Java2DFrameConverter]
           [org.bytedeco.ffmpeg.global avcodec]
           [java.awt Color Font Graphics2D RenderingHints]
           [java.awt.image BufferedImage]))

(defrecord JavaCvMedia []
  p/IMediaProbe
  (probe [_ source-uri]
    (with-open [g (FFmpegFrameGrabber. ^String source-uri)]
      (.start g)
      {:container   (.getFormat g)
       :duration-ms (units/us->ms (.getLengthInTime g))   ; µs -> ms at the boundary
       :has-audio?  (pos? (.getAudioChannels g))
       :audio-codec (.getAudioCodecName g)
       :sample-rate (long (.getSampleRate g))
       :channels    (long (.getAudioChannels g))}))

  p/IAudioExtractor
  (extract-audio [_ source-uri out-path {:keys [sample-rate channels]}]
    (with-open [g (FFmpegFrameGrabber. ^String source-uri)]
      (.start g)
      (with-open [rec (FFmpegFrameRecorder. ^String out-path (int channels))]
        ;; Re-encode decoded audio to signed-16 little-endian PCM in a WAV
        ;; container — the lowest-common-denominator ASR input format.
        (doto rec
          (.setFormat "wav")
          (.setAudioCodec avcodec/AV_CODEC_ID_PCM_S16LE)
          (.setSampleRate (int sample-rate))
          (.setAudioChannels (int channels))
          (.start))
        ;; Pull audio frames until the stream is exhausted. grabSamples returns
        ;; nil at EOF; video frames are skipped (audio-only recorder).
        (loop []
          (when-let [^Frame frame (.grabSamples g)]
            (.record rec frame)
            (recur)))
        (.stop rec))
      out-path)))

(defn make-backend
  "Construct the JavaCV media backend (satisfies IMediaProbe + IAudioExtractor).
   Call from the wiring/boundary layer under the :ffmpeg alias, e.g. via
   (requiring-resolve 'vtranslate.engine.collect.ffmpeg/make-backend)."
  []
  (->JavaCvMedia))

(defn- draw-lines!
  "Draw `lines` bottom-centered onto BufferedImage `img`, each line white with a
   1px black drop-shadow for legibility over any picture."
  [^BufferedImage img lines ^long font-size]
  (let [g2 ^Graphics2D (.getGraphics img)
        h  (.getHeight img)
        w  (.getWidth img)]
    (doto g2
      (.setRenderingHint RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)
      (.setFont (Font. "SansSerif" Font/BOLD (int font-size))))
    (let [fm     (.getFontMetrics g2)
          line-h (.getHeight fm)
          n      (count lines)
          pad    (int (* 0.06 h))]
      (dorun
       (map-indexed
        (fn [i ^String line]
          (let [tw (.stringWidth fm line)
                x  (int (/ (- w tw) 2))
                y  (int (- h pad (* (- n 1 i) line-h)))]
            (.setColor g2 Color/BLACK)
            (.drawString g2 line (inc x) (inc y))
            (.setColor g2 Color/WHITE)
            (.drawString g2 line x y)))
        lines)))
    (.dispose g2)))

(defn burn-hardsub
  "Burn subtitle lines into `source-uri`, writing an H.264/AAC mp4 to `out-path`.
   `lines-at` maps a frame timestamp (ms) to the lines to draw (nil = none): video
   frames with active lines are re-encoded with Graphics2D text drawn on the
   decoded picture; all other frames pass through untouched. => out-path.

   In-process only (bundled bytedeco native). This box's ffmpeg has no
   libass/freetype, so burn-in CANNOT use an ffmpeg subtitles= filter — the text
   is drawn onto decoded frames here."
  [^String source-uri ^String out-path lines-at {:keys [font-size] :or {font-size 28}}]
  (with-open [g (FFmpegFrameGrabber. source-uri)]
    (.start g)
    (let [w         (.getImageWidth g)
          h         (.getImageHeight g)
          ach       (.getAudioChannels g)
          grab-conv (Java2DFrameConverter.)
          back-conv (Java2DFrameConverter.)]
      (with-open [rec (FFmpegFrameRecorder. out-path (int w) (int h) (int ach))]
        (doto rec
          (.setFormat "mp4")
          (.setVideoCodec avcodec/AV_CODEC_ID_H264)
          (.setFrameRate (.getFrameRate g))
          (.setSampleRate (.getSampleRate g))
          (.setAudioChannels (int ach))
          (.setAudioCodec avcodec/AV_CODEC_ID_AAC)
          (.start))
        (loop []
          (when-let [^Frame frame (.grab g)]
            (let [lines (when (.image frame)
                          (lines-at (units/us->ms (.timestamp frame))))]
              (if (seq lines)
                (let [img (.convert grab-conv frame)]
                  (draw-lines! img lines font-size)
                  (.record rec (.convert back-conv img)))
                (.record rec frame)))
            (recur)))
        (.stop rec))
      out-path)))