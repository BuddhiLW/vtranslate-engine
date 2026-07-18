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
            [vtranslate.engine.collect.units :as units]
            [clojure.string :as string])
  (:import [org.bytedeco.javacv FFmpegFrameGrabber FFmpegFrameRecorder Frame
            Java2DFrameConverter]
           [org.bytedeco.ffmpeg.global avcodec avformat avutil]
           [org.bytedeco.ffmpeg.avformat AVFormatContext AVStream AVIOContext
            AVInputFormat AVOutputFormat]
           [org.bytedeco.ffmpeg.avcodec AVPacket AVCodecParameters AVCodec]
           [org.bytedeco.ffmpeg.avutil AVRational AVDictionary]
           [org.bytedeco.javacpp Pointer BytePointer PointerPointer]
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

(defn- configure-recorder!
  "Configure + start `rec` for H.264 video / AAC audio matching grabber `g`."
  [^FFmpegFrameRecorder rec ^FFmpegFrameGrabber g ^long ach]
  (doto rec
    (.setFormat "mp4")
    (.setVideoCodec avcodec/AV_CODEC_ID_H264)
    (.setFrameRate (.getFrameRate g))
    (.setSampleRate (.getSampleRate g))
    (.setAudioChannels (int ach))
    (.setAudioCodec avcodec/AV_CODEC_ID_AAC)
    (.start)))

(defn- transcode-frames!
  "Copy every frame g->rec, drawing active `lines-at` subtitle lines onto video
   frames that carry any (via the two converters); other frames pass through."
  [^FFmpegFrameGrabber g ^FFmpegFrameRecorder rec
   ^Java2DFrameConverter grab-conv ^Java2DFrameConverter back-conv lines-at font-size]
  (loop []
    (when-let [^Frame frame (.grab g)]
      (let [lines (when (.image frame)
                    (lines-at (units/us->ms (.timestamp frame))))]
        (if (seq lines)
          (let [img (.convert grab-conv frame)]
            (draw-lines! img lines font-size)
            (.record rec (.convert back-conv img)))
          (.record rec frame)))
      (recur))))

(defn burn-hardsub
  "Burn subtitle lines into `source-uri`, writing an H.264/AAC mp4 to `out-path`.
   `lines-at` maps a frame timestamp (ms) to the lines to draw (nil = none): video
   frames with active lines are re-encoded with Graphics2D text drawn on the
   decoded picture; all other frames pass through untouched. => out-path.

   In-process only (bundled bytedeco native). Grabber, recorder and both frame
   converters are released (reverse acquisition order) on every path via with-open.
   This box's ffmpeg has no libass/freetype, so burn-in draws onto decoded frames
   here rather than via an ffmpeg subtitles= filter."
  [^String source-uri ^String out-path lines-at {:keys [font-size] :or {font-size 28}}]
  (with-open [g (FFmpegFrameGrabber. source-uri)]
    (.start g)
    (let [w   (.getImageWidth g)
          h   (.getImageHeight g)
          ach (.getAudioChannels g)]
      (with-open [grab-conv (Java2DFrameConverter.)
                  back-conv (Java2DFrameConverter.)
                  rec       (FFmpegFrameRecorder. out-path (int w) (int h) (int ach))]
        (configure-recorder! rec g ach)
        (transcode-frames! g rec grab-conv back-conv lines-at font-size)
        (.stop rec))
      out-path)))

(defn- mov-text-sample
  "tx3g/mov_text sample bytes for `text`: uint16 big-endian length prefix + UTF-8."
  ^bytes [^String text]
  (let [tb  (.getBytes text "UTF-8")
        n   (alength tb)
        out (byte-array (+ 2 n))]
    (aset-byte out 0 (unchecked-byte (bit-shift-right n 8)))
    (aset-byte out 1 (unchecked-byte (bit-and n 0xff)))
    (System/arraycopy tb 0 out 2 n)
    out))

(defn- check!
  "Raise on a negative FFmpeg FFI return `ret` (carrying `op` + code) so the
   composer boundary maps it to an :error/compose-failed Result. => `ret`."
  ^long [^String op ^long ret]
  (when (neg? ret)
    (throw (ex-info (str "ffmpeg " op " failed (" ret ")") {:op op :code ret})))
  ret)

(defn- open-input!
  "Open + probe the input container into `ictx` (checked)."
  [^AVFormatContext ictx ^String source-uri]
  (let [^AVInputFormat no-ifmt nil
        ^PointerPointer no-opts nil]
    (check! "avformat_open_input"
            (avformat/avformat_open_input ictx source-uri no-ifmt no-opts))
    (check! "avformat_find_stream_info"
            (avformat/avformat_find_stream_info ictx no-opts))))

(defn- open-output!
  "Allocate the mp4 output context into `octx` (checked)."
  [^AVFormatContext octx ^String out-path]
  (let [^AVOutputFormat no-ofmt nil]
    (check! "avformat_alloc_output_context2"
            (avformat/avformat_alloc_output_context2 octx no-ofmt "mp4" out-path))))

(defn- copy-input-streams!
  "Stream-copy every input stream's codec parameters into a fresh output stream."
  [^AVFormatContext ictx ^AVFormatContext octx]
  (let [^AVCodec no-codec nil]
    (dotimes [i (.nb_streams ictx)]
      (let [in-st  (.streams ictx i)
            out-st (avformat/avformat_new_stream octx no-codec)]
        (avcodec/avcodec_parameters_copy (.codecpar out-st) (.codecpar in-st))
        (.codec_tag (.codecpar out-st) 0)))))

(defn- add-subtitle-stream!
  "Add a mov_text subtitle stream to `octx`, tagging `lang` when non-blank.
   => {:idx sub-stream-index :tb sub-stream-time-base}."
  [^AVFormatContext octx ^String lang]
  (let [^AVCodec no-codec nil
        sub-st  (avformat/avformat_new_stream octx no-codec)
        sub-idx (dec (.nb_streams octx))
        sp      (.codecpar sub-st)]
    (.codec_id sp avcodec/AV_CODEC_ID_MOV_TEXT)
    (.codec_type sp avutil/AVMEDIA_TYPE_SUBTITLE)
    (.num (.time_base sub-st) 1)
    (.den (.time_base sub-st) 1000)
    (when (seq lang)
      (let [meta (AVDictionary. (Pointer.))]
        (avutil/av_dict_set meta "language" lang 0)
        (.metadata sub-st meta)))
    {:idx sub-idx :tb (.time_base sub-st)}))

(defn- open-avio!
  "Open the output file's AVIO and attach it to `octx` (checked)."
  [^AVFormatContext octx ^String out-path]
  (let [pb (AVIOContext. (Pointer.))]
    (check! "avio_open" (avformat/avio_open pb out-path avformat/AVIO_FLAG_WRITE))
    (.pb octx pb)))

(defn- mux-cue!
  "Write one subtitle cue as an interleaved mov_text packet on `sub-idx` (checked);
   frees the packet on every path."
  [^AVFormatContext octx sub-idx ^AVRational sub-tb ^AVRational ms
   {:keys [start-ms end-ms lines]}]
  (let [payload (mov-text-sample (string/join "\n" lines))
        spkt    (avcodec/av_packet_alloc)]
    (try
      (avcodec/av_new_packet spkt (alength payload))
      (.put (.data spkt) payload)
      (.stream_index spkt (int sub-idx))
      (.pts spkt (avutil/av_rescale_q start-ms ms sub-tb))
      (.dts spkt (avutil/av_rescale_q start-ms ms sub-tb))
      (.duration spkt (avutil/av_rescale_q (- end-ms start-ms) ms sub-tb))
      (check! "av_interleaved_write_frame(subtitle)"
              (avformat/av_interleaved_write_frame octx spkt))
      (finally
        (avcodec/av_packet_free spkt)))))

(defn- mux-body!
  "Interleave subtitle `cues` into the A/V stream-copy loop: cues drain by start-ms
   up to each source packet's pts; the tail flushes any remaining cues."
  [^AVFormatContext ictx ^AVFormatContext octx ^AVPacket pkt cues sub-idx ^AVRational sub-tb]
  (let [ms      (doto (AVRational.) (.num 1) (.den 1000))
        pending (atom (vec (sort-by :start-ms cues)))
        drain!  (fn [upto]
                  (loop []
                    (when-let [c (first @pending)]
                      (when (<= (:start-ms c) upto)
                        (mux-cue! octx sub-idx sub-tb ms c)
                        (swap! pending rest)
                        (recur)))))]
    (loop []
      (when (>= (avformat/av_read_frame ictx pkt) 0)
        (let [in-st  (.streams ictx (.stream_index pkt))
              out-st (.streams octx (.stream_index pkt))
              pts-ms (if (= (.pts pkt) avutil/AV_NOPTS_VALUE)
                       0
                       (avutil/av_rescale_q (.pts pkt) (.time_base in-st) ms))]
          (drain! pts-ms)
          (avcodec/av_packet_rescale_ts pkt (.time_base in-st) (.time_base out-st))
          (check! "av_interleaved_write_frame(av)"
                  (avformat/av_interleaved_write_frame octx pkt))
          (avcodec/av_packet_unref pkt)
          (recur))))
    (drain! Long/MAX_VALUE)))

(defn- release-soft-mux!
  "Release soft-mux native resources in reverse acquisition order (packet, AVIO,
   output context, input context); each guard tolerates a partial acquisition."
  [^AVFormatContext ictx ^AVFormatContext octx ^AVPacket pkt]
  (when (and pkt (not (.isNull pkt)))
    (avcodec/av_packet_free pkt))
  (when (and octx (not (.isNull octx)))
    (let [pb (.pb octx)]
      (when (and pb (not (.isNull pb)))
        (avformat/avio_closep pb)))
    (avformat/avformat_free_context octx))
  (when (and ictx (not (.isNull ictx)))
    (avformat/avformat_close_input ictx)))

(defn soft-mux
  "Embed subtitle `cues` (seq of {:start-ms :end-ms :lines}) into `source-uri` as a
   selectable mov_text subtitle stream, stream-copying audio+video (no re-encode),
   writing an mp4 to `out-path`. `lang` is an ISO/BCP-47 tag for the track (nil to
   skip). => out-path.

   In-process raw avformat (bundled bytedeco native): each cue becomes a tx3g
   mov_text sample dts-interleaved into the A/V copy loop. Raises on a negative
   FFmpeg return code; releases every native context (input, output, AVIO, packet)
   in reverse acquisition order on every path."
  [^String source-uri ^String out-path cues ^String lang]
  (let [ictx (AVFormatContext. (Pointer.))
        octx (AVFormatContext. (Pointer.))
        pkt  (avcodec/av_packet_alloc)]
    (try
      (open-input! ictx source-uri)
      (open-output! octx out-path)
      (copy-input-streams! ictx octx)
      (let [{:keys [idx tb]} (add-subtitle-stream! octx lang)
            ^PointerPointer no-opts nil]
        (open-avio! octx out-path)
        (check! "avformat_write_header" (avformat/avformat_write_header octx no-opts))
        (mux-body! ictx octx pkt cues idx tb)
        (check! "av_write_trailer" (avformat/av_write_trailer octx))
        out-path)
      (finally
        (release-soft-mux! ictx octx pkt)))))