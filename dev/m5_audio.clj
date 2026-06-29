(ns m5-audio
  "M5 dev harness — synthesise a real audio fixture (AAC-in-MP4) and drive the
   JavaCV Collect backend end to end, so REPL verification stays one-liners
   instead of giant inline forms. NOT on the src path — load-file it:
     (load-file \"/home/leibniz/PP/vtranslate/vtranslate-engine/dev/m5_audio.clj\")
     (m5-audio/run)"
  (:require [vtranslate.engine.collect.ffmpeg :as ff]
            [vtranslate.engine.collect.protocols :as p]
            [vtranslate.engine.collect.audio :as audio]
            [vtranslate.engine.collect.port :as cp]
            [vtranslate.engine.api :as api]
            [vtranslate.engine.port.transcriber :as p.asr]
            [vtranslate.engine.port.translator :as p.tr]
            [vtranslate.engine.port.subtitle :as p.sub]
            [hive-dsl.result :as r])
  (:import [org.bytedeco.javacv FFmpegFrameRecorder FFmpegFrameGrabber]
           [org.bytedeco.ffmpeg.global avcodec]
           [java.nio ShortBuffer Buffer]
           [java.io File]))

(defn make-aac-fixture!
  "Write `secs` of a 440 Hz sine as AAC-in-MP4 (mono, 44.1 kHz). Returns the path.
   A real demux target — extract-audio must transcode AAC 44.1k -> PCM 16k."
  [secs]
  (let [path  (.getPath (File/createTempFile "vt-fixture-" ".mp4"))
        sr    44100
        rec   (doto (FFmpegFrameRecorder. ^String path (int 1))
                (.setFormat "mp4")
                (.setAudioCodec avcodec/AV_CODEC_ID_AAC)
                (.setSampleRate (int sr))
                (.setAudioChannels (int 1))
                (.start))
        chunk (int (/ sr 10))]                       ; 0.1 s blocks
    (dotimes [i (int (* secs 10))]
      (let [sb (ShortBuffer/allocate chunk)]
        (dotimes [k chunk]
          (let [t (/ (double (+ (* i chunk) k)) sr)]
            (.put sb (short (* 10000 (Math/sin (* 2 Math/PI 440 t)))))))
        (.flip sb)
        (.recordSamples rec (int sr) (int 1) (into-array Buffer [sb]))))
    (.stop rec)
    (.release rec)
    path))

(defn inspect-wav
  "Re-open a produced WAV with a fresh grabber and report its real format —
   independent proof the extracted artifact is what we claim."
  [path]
  (with-open [g (FFmpegFrameGrabber. ^String path)]
    (.start g)
    {:format      (.getFormat g)
     :codec       (.getAudioCodecName g)
     :sample-rate (long (.getSampleRate g))
     :channels    (long (.getAudioChannels g))
     :duration-ms (long (/ (.getLengthInTime g) 1000))}))

(defn run
  "Full M5 smoke: synth fixture -> backend probe -> backend extract -> re-probe
   the WAV -> sugar-layer probe (fs check + ProbeInfo projection)."
  []
  (let [src     (make-aac-fixture! 2)
        backend (ff/make-backend)
        probed  (p/probe backend src)
        out     (.getPath (File/createTempFile "vt-out-" ".wav"))
        ext     (p/extract-audio backend src out {:sample-rate 16000 :channels 1})
        wav     (inspect-wav out)
        sugar   (audio/probe backend src)]
    {:fixture        src
     :backend-probe  probed
     :extract-out    ext
     :extract-exists (.exists (File. ^String out))
     :extract-bytes  (.length (File. ^String out))
     :wav-facts      wav
     :sugar-probe    sugar}))

(defn run-pipeline
  "Drive the WHOLE api/run-job through the REAL CollectMediaPort (which actually
   demuxes the synth fixture) + stub transcriber/translator/renderer. Proves the
   M5 collect layer integrates into the M2 pipeline via the port.media bridge."
  []
  (let [src   (make-aac-fixture! 2)
        ports {:media       (cp/collect-media-port)          ; real JavaCV-backed port
               :transcriber (reify p.asr/ITranscriber
                              (transcribe [_ _audio _lang _opts]
                                (r/ok {:segments [{:start-ms 0    :end-ms 1000 :text "hello" :confidence 0.9}
                                                  {:start-ms 1000 :end-ms 2000 :text "world" :confidence 0.8}]})))
               :translator  (reify p.tr/ITranslator
                              (translate-batch [_ texts _s _t _opts]
                                (r/ok (mapv #(str % "-pt") texts))))
               :renderer    (reify p.sub/ISubtitleRenderer
                              (render-bytes [_ track]
                                (r/ok (str "TRACK:" (:format track) ":" (count (:cues track)) "cues"))))}
        spec  {:job-id "j-m5" :source src
               :source-language "en" :target-language "pt-BR"
               :asset-kind :media/video :format :format/srt}
        res   (api/run-job ports spec)]
    (if (r/ok? res)
      (let [{:keys [job transcript translated subtitle-track rendered]} (:ok res)]
        {:ok?           true
         :job-state     (:adt/variant (:state job))
         :transcript-id (:transcript-id job)
         :subtitle-id   (:subtitle-id job)
         :seg-count     (count (:segments transcript))
         :unit-count    (count (:units translated))
         :cue-count     (count (:cues subtitle-track))
         :rendered      rendered})
      res)))
