(ns vtranslate.engine.native.speech-fixture
  "Local artifacts the ASR/VAD native suites read. Nothing is downloaded: each
   artifact is looked up on disk and a suite whose artifact is missing FAILS
   with the exact path it looked for. Running a native suite is a claim that its
   artifact is present.

   - speech media: VT_SPEECH_MEDIA, else ../corpus/campaign-video/jfk-ask-not_24s.mp4
     (US Government film, public domain; see ../corpus/CREDITS.md). Decoded to
     16 kHz mono by collect.ffmpeg, so these suites also need :ffmpeg.
   - whisper.cpp weights: VT_WHISPER_MODEL, else models/ggml-base.bin, else
     ../models/ggml-base.bin.
   - Silero VAD: VT_SILERO_MODEL, else models/silero_vad.onnx, else
     ../models/silero_vad.onnx."
  (:require [vtranslate.engine.adapters.transcriber.support :as sup]
            [vtranslate.engine.collect.ffmpeg :as ffmpeg]
            [vtranslate.engine.collect.protocols :as p]
            [vtranslate.engine.native.media-fixture :as fx])
  (:import [java.io File]
           [java.nio ByteBuffer ByteOrder]
           [java.nio.file Files OpenOption Path]))

(defn- first-existing [env & candidates]
  (let [paths (cons (System/getenv env) candidates)]
    (or (some #(when (and % (.isFile (File. ^String %))) (.getCanonicalPath (File. ^String %)))
              paths)
        (throw (ex-info (str "native suite artifact missing: set " env " or place it at one of "
                             (pr-str (remove nil? candidates)))
                        {:env env :looked-at (vec (remove nil? paths))})))))

(def speech-media
  (delay (first-existing "VT_SPEECH_MEDIA" "../corpus/campaign-video/jfk-ask-not_24s.mp4")))

(def whisper-model
  (delay (first-existing "VT_WHISPER_MODEL" "models/ggml-base.bin" "../models/ggml-base.bin")))

(def silero-model
  (delay (first-existing "VT_SILERO_MODEL" "models/silero_vad.onnx" "../models/silero_vad.onnx")))

(def speech-dir (delay (fx/temp-dir!)))

(def speech-wav
  "The speech media as a 16 kHz mono PCM WAV in a temp dir (made once)."
  (delay
    (let [out (str @speech-dir "/speech.wav")]
      (p/extract-audio (ffmpeg/make-backend) @speech-media out {:sample-rate 16000 :channels 1})
      out)))

(def speech-samples
  (delay (:samples (:ok (sup/read-wav-mono-floats @speech-wav)))))

(defn window
  "Samples [start-s, start-s + dur-s) of the speech clip, clipped to its end."
  ^floats [start-s dur-s]
  (let [^floats xs @speech-samples
        a (min (alength xs) (long (* 16000 start-s)))
        b (min (alength xs) (long (* 16000 (+ start-s dur-s))))]
    (java.util.Arrays/copyOfRange xs (int a) (int b))))

(defn write-wav!
  "Write mono float samples (clamped to [-1,1]) as 16-bit PCM WAV at `rate`."
  [path ^floats xs rate]
  (let [n  (alength xs)
        bb (doto (ByteBuffer/allocate (+ 44 (* 2 n))) (.order ByteOrder/LITTLE_ENDIAN))]
    (.put bb (.getBytes "RIFF")) (.putInt bb (+ 36 (* 2 n))) (.put bb (.getBytes "WAVEfmt "))
    (.putInt bb 16) (.putShort bb (short 1)) (.putShort bb (short 1))
    (.putInt bb (int rate)) (.putInt bb (int (* 2 rate)))
    (.putShort bb (short 2)) (.putShort bb (short 16))
    (.put bb (.getBytes "data")) (.putInt bb (* 2 n))
    (dotimes [i n]
      (.putShort bb (short (Math/round (* 32767.0 (max -1.0 (min 1.0 (double (aget xs i)))))))))
    (Files/write (Path/of ^String path (into-array String [])) (.array bb) (into-array OpenOption []))
    path))

(defn noise
  "Deterministic gaussian noise of amplitude `amp`."
  ^floats [n amp seed]
  (let [r (java.util.Random. (long seed)) xs (float-array n)]
    (dotimes [i n] (aset xs i (float (* amp (.nextGaussian r)))))
    xs))
