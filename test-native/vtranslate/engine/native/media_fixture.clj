(ns vtranslate.engine.native.media-fixture
  "Hermetic media for the native (:ffmpeg) contract suite. Everything is made
   in a fresh temp dir by the bundled bytedeco encoder (no system ffmpeg, no
   network): a 1 s 160x120 colour test pattern at 10 fps with a 16 kHz mono
   silent AAC track. Read-back helpers project containers and frames to plain
   EDN so schemas and goldens never see a native pointer or raw bytes."
  (:require [clojure.string :as str]
            [vtranslate.engine.calc.overlay :as overlay]
            [vtranslate.engine.domain.rendering :as rd])
  (:import [java.awt Color]
           [java.awt.image BufferedImage]
           [java.io File]
           [java.nio Buffer ShortBuffer]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [org.bytedeco.ffmpeg.avformat AVFormatContext AVInputFormat]
           [org.bytedeco.ffmpeg.avutil AVDictionaryEntry AVRational]
           [org.bytedeco.ffmpeg.global avcodec avformat avutil]
           [org.bytedeco.javacpp Pointer PointerPointer]
           [org.bytedeco.javacv FFmpegFrameGrabber FFmpegFrameRecorder Frame
            Java2DFrameConverter]))

(def width 160)
(def height 120)
(def fps 10)
(def frame-ms (quot 1000 fps))

(defn temp-dir!
  "A fresh temp directory. => absolute path string."
  []
  (str (Files/createTempDirectory "vt-native-" (into-array FileAttribute []))))

(defn delete-tree!
  "Remove `dir` and everything under it; best effort."
  [dir]
  (doseq [^File f (reverse (file-seq (File. ^String dir)))]
    (.delete f)))

(defmacro with-temp-dir
  "Bind `sym` to a fresh temp dir for `body`, deleting it afterwards."
  [[sym] & body]
  `(let [~sym (temp-dir!)]
     (try ~@body (finally (delete-tree! ~sym)))))

(defn- pattern-frame
  "Frame `i` of the test pattern: a flat colour that changes every frame."
  ^BufferedImage [i]
  (let [img (BufferedImage. width height BufferedImage/TYPE_3BYTE_BGR)
        g   (.getGraphics img)]
    (.setColor g (Color. (int (mod (* i 23) 256)) (int 90) (int 160)))
    (.fillRect g 0 0 width height)
    (.dispose g)
    img))

(defn synth-source!
  "Write the 1 s test pattern + silence mp4 (H.264/AAC) into `dir`. => path."
  ([dir] (synth-source! dir "source.mp4"))
  ([dir name]
   (let [path (str dir "/" name)]
     (with-open [rec  (FFmpegFrameRecorder. ^String path (int width) (int height) (int 1))
                 conv (Java2DFrameConverter.)]
       (doto rec
         (.setFormat "mp4")
         (.setVideoCodec avcodec/AV_CODEC_ID_H264)
         (.setFrameRate (double fps))
         (.setVideoBitrate 200000)
         (.setAudioCodec avcodec/AV_CODEC_ID_AAC)
         (.setSampleRate 16000)
         (.setAudioChannels 1)
         (.start))
       (dotimes [i fps]
         (.record rec ^Frame (.convert conv (pattern-frame i)))
         (.recordSamples rec 16000 1
                         (into-array Buffer [(ShortBuffer/wrap (short-array (quot 16000 fps)))])))
       (.stop rec))
     path)))

(defn rendered-track
  "A rendered SubtitleTrack in BCP-47 `language` from plain cue specs
   [{:start-ms :end-ms :lines} ...]. => SubtitleTrack (throws on bad input)."
  [cues language]
  (let [t (:ok (rd/make-subtitle-track {:id "t" :source-id "src" :language language
                                        :format :format/srt}))]
    (or (:ok (rd/render
              (reduce (fn [t [i c]] (rd/add-cue t (:ok (rd/make-cue (assoc c :index (inc i))))))
                      t (map-indexed vector cues))))
        (throw (ex-info "fixture track did not render" {:cues cues :language language})))))

(defn cue-active?
  "Oracle: whether any cue of `track` covers `t-ms` (calc.overlay semantics)."
  [track t-ms]
  (boolean (seq (overlay/active-lines (overlay/timeline track) t-ms))))

;; --- read-back ---------------------------------------------------------------

(defn- stream-language [st]
  (let [^AVDictionaryEntry e (avutil/av_dict_get (.metadata st) "language" nil 0)]
    (when (and e (not (.isNull e))) (.getString (.value e)))))

(defn container-report
  "Project the container at `path` to {:streams [{:type :codec :language}]
   :packets {stream-idx n} :subtitles [{:start-ms :end-ms :text}]}. Subtitle
   samples are decoded from their tx3g framing (uint16 BE length + UTF-8)."
  [^String path]
  (let [ictx (AVFormatContext. (Pointer.))
        ^AVInputFormat no-ifmt nil
        ^PointerPointer no-opts nil]
    (when (neg? (avformat/avformat_open_input ictx path no-ifmt no-opts))
      (throw (ex-info "cannot open container" {:path path})))
    (try
      (avformat/avformat_find_stream_info ictx no-opts)
      (let [streams (vec (for [i (range (.nb_streams ictx))]
                           (let [st (.streams ictx i) cp (.codecpar st)]
                             {:type     (.codec_type cp)
                              :codec    (.getString (avcodec/avcodec_get_name (.codec_id cp)))
                              :language (stream-language st)})))
            ms  (doto (AVRational.) (.num 1) (.den 1000))
            pkt (avcodec/av_packet_alloc)]
        (try
          (loop [packets {} subs []]
            (if (>= (avformat/av_read_frame ictx pkt) 0)
              (let [i   (.stream_index pkt)
                    st  (.streams ictx i)
                    tb  (.time_base st)
                    sub (when (= avutil/AVMEDIA_TYPE_SUBTITLE (.codec_type (.codecpar st)))
                          (let [n (.size pkt) b (byte-array n)
                                start (avutil/av_rescale_q (.pts pkt) tb ms)]
                            (.get (.data pkt) b)
                            {:start-ms start
                             :end-ms   (+ start (avutil/av_rescale_q (.duration pkt) tb ms))
                             :text     (String. b 2 (int (- n 2)) "UTF-8")}))]
                (avcodec/av_packet_unref pkt)
                (recur (update packets i (fnil inc 0)) (cond-> subs sub (conj sub))))
              {:streams streams :packets packets :subtitles subs}))
          (finally (avcodec/av_packet_free pkt))))
      (finally (avformat/avformat_close_input ictx)))))

(defn decoded-frames
  "Decode every video frame of `path` => [[t-ms BufferedImage(RGB copy)] ...]."
  [^String path]
  (with-open [g    (FFmpegFrameGrabber. path)
              conv (Java2DFrameConverter.)]
    (.start g)
    (loop [acc []]
      (if-let [f (.grabImage g)]
        (let [img (.convert conv f)
              cp  (BufferedImage. (.getWidth img) (.getHeight img) BufferedImage/TYPE_INT_RGB)]
          (.drawImage (.getGraphics cp) img 0 0 nil)
          (recur (conj acc [(quot (.timestamp f) 1000) cp])))
        acc))))

(defn- max-channel-diff ^long [^BufferedImage a ^BufferedImage b]
  (let [ch (fn [p s] (bit-and (bit-shift-right p s) 255))]
    (reduce max 0
            (for [x (range 0 (min (.getWidth a) (.getWidth b)) 2)
                  y (range 0 (min (.getHeight a) (.getHeight b)) 2)
                  :let [p (.getRGB a x y) q (.getRGB b x y)]
                  s [16 8 0]]
              (Math/abs (- (long (ch p s)) (long (ch q s))))))))

(def ^:private change-threshold
  "Per-channel difference above which a pixel was DRAWN on rather than merely
   re-encoded. H.264 re-encode noise on this flat pattern stays below 10, and
   caption text/outline/plate differ by well over 100."
  48)

(defn frame-observations
  "Compare each output frame with the source frame at the same timestamp.
   => [{:t-ms :cue-active? :changed?} ...]"
  [source-path out-path track]
  (let [src (into {} (decoded-frames source-path))]
    (vec (for [[t img] (decoded-frames out-path)
               :let [s (get src t)]
               :when s]
           {:t-ms        t
            :cue-active? (cue-active? track t)
            :changed?    (> (max-channel-diff s img) change-threshold)}))))

(defn partials
  "Leftover `.part-*` temp files in `dir` (composer.support/atomically)."
  [dir]
  (vec (sort (filter #(str/includes? % ".part-") (.list (File. ^String dir))))))
