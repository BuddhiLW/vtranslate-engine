(ns vtranslate.engine.collect.ffmpeg-cli
  "Collect: the system ffmpeg and ffprobe executables as a value. No
   bytedeco, no JavaCV; loads on any classpath.

   Everything about a command line is decided in calc.ffmpeg-args and the
   caption script in calc.ass; this namespace runs the argv through an
   IProcessRunner, reads what came back, and turns a non-zero exit into an
   exception carrying the exit code, the argv and the tail of stderr."
  (:require [clojure.string :as str]
            [vtranslate.engine.calc.ass :as ass]
            [vtranslate.engine.calc.encoding :as encoding]
            [vtranslate.engine.calc.ffmpeg-args :as args]
            [vtranslate.engine.collect.font-metrics :as font-metrics]
            [vtranslate.engine.collect.process :as process]
            [vtranslate.engine.collect.watermark :as mark]
            [vtranslate.engine.calc.watermark :as watermark]
            [clojure.java.io :as io])
  (:import [java.io File]))

(def ^:private stderr-tail-chars
  "How much of a failed run's stderr travels with the exception. ffmpeg
   repeats itself; the last lines carry the reason."
  4000)

(defn- tail [^String s n]
  (let [len (count s)]
    (if (> len n) (subs s (- len n)) s)))

(defn- reason
  "The last non-blank stderr line: the one that names the failure."
  [stderr]
  (some-> (last (remove str/blank? (str/split-lines (str stderr)))) str/trim))

(defrecord FfmpegCli [runner bin])

(defn ffmpeg-cli
  "The executables at `bin` (ffprobe taken from beside it) run through
   `runner`, default the host's processes."
  ([bin] (ffmpeg-cli process/system-runner bin))
  ([runner bin] (->FfmpegCli runner (str bin))))

(defn capable?
  "Whether this ffmpeg starts and its build lists the libass `subtitles`
   filter and `encoder`'s row (default libx264). False for a missing binary,
   a permission problem, or a build without either (Homebrew's, for one), so
   an :auto choice keeps the in-process path rather than failing every burn.

   A hardware encoder must also OPEN: one frame is encoded for real, because
   the listing names h264_nvenc on hosts that have no GPU at all."
  ([cli] (capable? cli args/default-encoder))
  ([cli encoder] (capable? cli encoder nil))
  ([{:keys [runner bin]} encoder opts]
   (let [table (args/encoders-from opts)]
     (and (process/starts? runner [bin "-version"])
          (args/capable?
           (into {} (for [listing (keys (args/capabilities-for table encoder))]
                      [listing (process/output-of runner (args/listing-args {:bin bin :listing listing}))]))
           table encoder)
          (or (not (:hardware? (args/encoder-spec table encoder)))
              (process/starts? runner (args/encoder-probe-args
                                       {:bin bin :encoder encoder :encoders table})))))))

(defn- probe-line
  "First stdout line of one ffprobe run, nil for an absent stream."
  [{:keys [runner bin]} source kind]
  (some-> (process/output-of runner (args/probe-args {:bin (args/probe-binary bin)
                                                      :source source :kind kind}))
          str/split-lines
          first))

(defn probe
  "The source's shape for calc.encoding: {:width :height :frame-rate
   :video-bitrate :audio-bitrate :audio?}. Throws when there is no readable
   video stream, since nothing can be burned into it."
  [cli source]
  (let [video (args/parse-video-probe (probe-line cli source :video))
        audio (args/parse-audio-probe (probe-line cli source :audio))]
    (when-not video
      (throw (ex-info "ffprobe found no video stream to burn into"
                      {:source (str source) :probe (args/probe-binary (:bin cli))})))
    (assoc video
           :audio? (boolean audio)
           :audio-bitrate (:audio-bitrate audio))))

(defn- write-script!
  "The ASS script beside `out`, so it lives on the same volume and is
   cleaned with the same temp. => the script's File."
  ^File [out document]
  (let [f (File. (str out ".ass"))]
    (spit f document :encoding "UTF-8")
    f))

(defn- run-burn!
  "One ffmpeg run; a non-zero exit throws with the reason in the message,
   because the composer boundary keeps only class + message of a throw."
  [{:keys [runner]} argv]
  (let [{:keys [exit stderr]} (process/exec! runner argv)]
    (when-not (zero? exit)
      (throw (ex-info (str "ffmpeg exited " exit
                           (when-let [why (reason stderr)] (str ": " why)))
                      {:exit exit
                       :argv argv
                       :stderr (tail (str stderr) stderr-tail-chars)})))))

(defn burn-hardsub
  "Burn `cues` (plain overlay cues {:start-ms :end-ms :lines}) into
   `source`, writing an H.264/AAC mp4 to `out` with `cli`.

   `opts` carries `:quality` (a calc.encoding preset, default :source), any
   calc.captions style key, the encoder's preset (`:preset` for x264,
   default veryfast; `:nvenc-preset` for NVENC, default p4), and
   `:watermark?` (the VTranslate mark in the corner). The ASS script is
   written beside `out` and removed on every path; `out` is left to the
   caller's atomic-rename wrapper to keep or discard.

   `encoder` (a calc.ffmpeg-args `encoders` key, default :libx264) is the
   burner's, not the job's, so it is an argument and never read from `opts`.

   The mark is sized against the PLAN's height, not the source's: it is
   composited after the scale, so the frame it lands on is the output's.
   => out."
  ([cli source out cues opts]
   (burn-hardsub cli source out cues opts args/default-encoder))
  ([{:keys [bin] :as cli} source out cues opts encoder]
   (let [{:keys [width height frame-rate video-bitrate audio-bitrate audio?]}
         (probe cli source)
         plan    (encoding/plan {:source-width width
                                 :source-height height
                                 :source-video-bitrate video-bitrate
                                 :source-audio-bitrate audio-bitrate
                                 :frame-rate frame-rate
                                 :quality (get opts :quality :source)})
         script  (write-script! out (ass/document {:width width :height height} opts cues
                                                  (font-metrics/measure opts)))
         threads (encoding/encoder-threads (.availableProcessors (Runtime/getRuntime)))
         mark    (when (:watermark? opts)
                   (let [{:keys [path] :as geo}
                         (mark/png-for (.getParent (io/file out)) (:height plan))]
                     {:png path :position (watermark/overlay-position geo)}))]
     (try
       (run-burn! cli (args/burn-args {:bin bin :source source :out out
                                       :ass-path (.getPath script) :plan plan
                                       :encoder encoder
                                       :encoders (args/encoders-from opts)
                                       :preset (args/encoder-preset encoder opts)
                                       :threads threads :audio? audio?
                                       :watermark mark}))
       out
       (finally
         (.delete script))))))
