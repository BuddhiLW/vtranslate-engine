(ns vtranslate.engine.adapters.composer.composer-int-test
  "OPT-IN native (:ffmpeg) integration suite for the video composers (run:
   clojure -M:test:itest:ffmpeg). Loads bytedeco; excluded from the default unit
   run (test-int/ is off the default classpath — path isolation IS the gate).
   Synthesizes a hermetic source mp4, exercises the REAL soft-mux + hardsub
   adapters end-to-end, and reuses the shared check-composer LSP contract. The
   muxed output is projected to plain EDN (streams + codecs) before snapshotting."
  (:require [clojure.test :refer [deftest is]]
            [hive-test.golden :refer [deftest-golden]]
            [vtranslate.engine.collect.ffmpeg :as ffmpeg]
            [vtranslate.engine.adapters.composer.softmux :as softmux]
            [vtranslate.engine.adapters.composer.hardsub :as hardsub]
            [vtranslate.engine.domain.rendering :as rd]
            [vtranslate.engine.contract.ports-contract :as ports])
  (:import [org.bytedeco.javacv FFmpegFrameRecorder Java2DFrameConverter]
           [org.bytedeco.ffmpeg.global avcodec avformat]
           [org.bytedeco.ffmpeg.avformat AVFormatContext AVInputFormat]
           [org.bytedeco.javacpp Pointer PointerPointer]
           [java.awt Color]
           [java.awt.image BufferedImage]))

(def ^:private tmp-dir (System/getProperty "java.io.tmpdir"))

(defn- out-path [nm] (str tmp-dir "/vt-composer-" nm ".mp4"))

(defn- synth-source!
  "Write a hermetic 2s H.264 video-only mp4 to `path` (20 frames @ 10fps,
   320x240). => path."
  [^String path]
  (with-open [rec  (FFmpegFrameRecorder. path (int 320) (int 240) (int 0))
              conv (Java2DFrameConverter.)]
    (doto rec
      (.setFormat "mp4")
      (.setVideoCodec avcodec/AV_CODEC_ID_H264)
      (.setFrameRate 10.0)
      (.setVideoBitrate 200000)
      (.start))
    (dotimes [i 20]
      (let [img (BufferedImage. 320 240 BufferedImage/TYPE_3BYTE_BGR)
            g   (.getGraphics img)]
        (.setColor g (Color. (int (mod (* i 12) 256)) (int 100) (int 150)))
        (.fillRect g 0 0 320 240)
        (.dispose g)
        (.record rec (.convert conv img))))
    (.stop rec))
  path)

(defn- project-streams
  "Probe `path` into a stable EDN projection: stream count + each stream's
   {:type codec-type-int :codec codec-name}. Never snapshots raw bytes."
  [path]
  (let [ictx (AVFormatContext. (Pointer.))
        ^AVInputFormat no-ifmt nil
        ^PointerPointer no-opts nil]
    (try
      (avformat/avformat_open_input ictx path no-ifmt no-opts)
      (avformat/avformat_find_stream_info ictx no-opts)
      {:nb-streams (.nb_streams ictx)
       :streams (vec (for [i (range (.nb_streams ictx))]
                       (let [cp (.codecpar (.streams ictx i))]
                         {:type  (.codec_type cp)
                          :codec (.getString (avcodec/avcodec_get_name (.codec_id cp)))})))}
      (finally (avformat/avformat_close_input ictx)))))

(def ^:private cues
  [{:start-ms 0 :end-ms 800 :lines ["hello world"]}
   {:start-ms 800 :end-ms 1600 :lines ["second cue" "two lines"]}])

(defn- sample-track []
  (let [t  (:ok (rd/make-subtitle-track {:id "s" :source-id "c" :language "pt-BR" :format :format/srt}))
        c1 (:ok (rd/make-cue {:index 1 :start-ms 0 :end-ms 800 :lines ["hello world"]}))
        c2 (:ok (rd/make-cue {:index 2 :start-ms 800 :end-ms 1600 :lines ["second cue" "two lines"]}))]
    (:ok (rd/render (rd/add-cue (rd/add-cue t c1) c2)))))

;; GOLDEN — native soft-mux adds a selectable mov_text stream, stream-copying A/V.
(deftest-golden softmux-native-streams-golden
  "test/golden/composer-softmux-native-streams.edn"
  (let [src (synth-source! (out-path "src-golden"))
        out (out-path "softmux-golden")]
    (ffmpeg/soft-mux src out cues "eng")
    (project-streams out)))

;; GOLDEN — mov_text tx3g sample framing (uint16 BE length prefix + UTF-8), as
;; unsigned bytes.
(deftest-golden mov-text-sample-golden
  "test/golden/composer-mov-text-sample.edn"
  (mapv #(bit-and % 0xff) (#'ffmpeg/mov-text-sample "Olá\nsub")))

;; LSP — the REAL SoftMuxComposer satisfies the shared check-composer contract on
;; a native run, and its output carries the appended mov_text subtitle stream.
(deftest softmux-adapter-satisfies-contract
  (let [src      (synth-source! (out-path "src-soft"))
        composer (softmux/make-composer {:composer-opts {}})]
    (ports/check-composer composer src (sample-track) {:output-uri (out-path "soft-contract")})
    (is (= [0 3] (mapv :type (:streams (project-streams (out-path "soft-contract")))))
        "output = source video stream + appended mov_text subtitle stream")))

;; LSP — the REAL HardsubComposer satisfies the same contract; burn-in re-encodes
;; to an H.264 video stream.
(deftest hardsub-adapter-satisfies-contract
  (let [src      (synth-source! (out-path "src-hard"))
        composer (hardsub/make-composer {:composer-opts {:font-size 24}})]
    (ports/check-composer composer src (sample-track) {:output-uri (out-path "hard-contract")})
    (is (= "h264" (:codec (first (:streams (project-streams (out-path "hard-contract"))))))
        "burned output is H.264 video")))
