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
  (:import [org.bytedeco.javacv FFmpegFrameGrabber FFmpegFrameRecorder Frame]
           [org.bytedeco.ffmpeg.global avcodec]))

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
