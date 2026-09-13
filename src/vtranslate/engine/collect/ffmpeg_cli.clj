(ns vtranslate.engine.collect.ffmpeg-cli
  "System-ffmpeg boundary: runs the ffmpeg and ffprobe executables as
   subprocesses. No bytedeco, no JavaCV; loads on any classpath.

   The burn is one process: libass renders the cues from an ASS script the
   pure calc.ass wrote, libx264 encodes. Everything about the command line
   is decided in calc.ffmpeg-args; this namespace only writes the script,
   runs the argv, and turns a non-zero exit into an exception the composer
   boundary maps to :error/compose-failed."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [vtranslate.engine.calc.ass :as ass]
            [vtranslate.engine.calc.encoding :as encoding]
            [vtranslate.engine.calc.ffmpeg-args :as args])
  (:import [java.io File IOException]
           [java.lang ProcessBuilder$Redirect]))

(def ^:private stderr-tail-chars
  "How much of a failed run's stderr travels with the exception. ffmpeg
   repeats itself; the last lines carry the reason."
  4000)

(defn- tail [^String s n]
  (let [len (count s)]
    (if (> len n) (subs s (- len n)) s)))

(defn exec!
  "Run `argv` to completion. stdout is discarded, stderr captured. => {:exit
   n :stderr s}. Throws IOException when the executable cannot be started."
  [argv]
  (let [pb (doto (ProcessBuilder. ^java.util.List (vec (map str argv)))
             (.redirectOutput ProcessBuilder$Redirect/DISCARD)
             (.redirectErrorStream false))
        p  (.start pb)
        err (with-open [r (io/reader (.getErrorStream p))]
              (slurp r))
        exit (.waitFor p)]
    {:exit exit :stderr err}))

(defn- output!
  "Run `argv` to completion, stderr discarded, stdout captured. => {:exit n
   :out s}. Throws IOException when the executable cannot be started."
  [argv]
  (let [pb (doto (ProcessBuilder. ^java.util.List (vec (map str argv)))
             (.redirectError ProcessBuilder$Redirect/DISCARD))
        p  (.start pb)
        out (with-open [r (io/reader (.getInputStream p))]
              (slurp r))
        exit (.waitFor p)]
    {:exit exit :out out}))

(defn- lists?
  "Whether `bin` prints `name` as a whole word when asked for `listing`
   (-filters, -encoders). A build without the feature simply omits the row."
  [bin listing name]
  (let [{:keys [exit out]} (output! [bin "-hide_banner" listing])]
    (and (zero? exit)
         (boolean (re-find (re-pattern (str "(?m)^\\s*\\S+\\s+" name "\\s")) out)))))

(defn available?
  "Whether `bin` is an ffmpeg this burn can use: it starts, its build has the
   libass `subtitles` filter and the `libx264` encoder. A build missing
   either (Homebrew's, for one) is reported unavailable, so :auto keeps the
   in-process path rather than failing every burn at run time. False too for
   a missing binary or a permission problem."
  [bin]
  (try
    (and (zero? (:exit (exec! [bin "-version"])))
         (lists? bin "-filters" "subtitles")
         (lists? bin "-encoders" "libx264"))
    (catch IOException _ false)
    (catch SecurityException _ false)))

(defn- probe-line!
  "First stdout line of an ffprobe run, or nil for a stream that is absent.
   ffprobe writes the CSV to stdout, so this run captures it."
  [argv]
  (let [pb (doto (ProcessBuilder. ^java.util.List (vec (map str argv)))
             (.redirectError ProcessBuilder$Redirect/DISCARD))
        p  (.start pb)
        out (with-open [r (io/reader (.getInputStream p))]
              (slurp r))
        exit (.waitFor p)]
    (when (zero? exit)
      (some-> out str/split-lines first))))

(defn probe
  "The source's shape for calc.encoding: {:width :height :frame-rate
   :video-bitrate :audio-bitrate :audio?}. Throws when there is no readable
   video stream, since nothing can be burned into it."
  [bin source]
  (let [probe-bin (args/probe-binary bin)
        video (args/parse-video-probe
               (probe-line! (args/probe-args {:bin probe-bin :source source :kind :video})))
        audio (args/parse-audio-probe
               (probe-line! (args/probe-args {:bin probe-bin :source source :kind :audio})))]
    (when-not video
      (throw (ex-info "ffprobe found no video stream to burn into"
                      {:source (str source) :probe probe-bin})))
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

(defn burn-hardsub
  "Burn `cues` (plain overlay cues {:start-ms :end-ms :lines}) into
   `source`, writing an H.264/AAC mp4 to `out` with the system `bin`.

   `opts` carries `:quality` (a calc.encoding preset, default :source), any
   calc.captions style key, and `:preset` (x264, default veryfast). The ASS
   script is written beside `out` and removed on every path. A non-zero exit
   throws ex-info with the exit code, the argv and the tail of stderr, and
   `out` is left to the caller's atomic-rename wrapper to discard. => out."
  [bin source out cues opts]
  (let [{:keys [width height frame-rate video-bitrate audio-bitrate audio?]}
        (probe bin source)
        plan     (encoding/plan {:source-width width
                                 :source-height height
                                 :source-video-bitrate video-bitrate
                                 :source-audio-bitrate audio-bitrate
                                 :frame-rate frame-rate
                                 :quality (get opts :quality :source)})
        script   (write-script! out (ass/document {:width width :height height} opts cues))
        threads  (encoding/encoder-threads (.availableProcessors (Runtime/getRuntime)))
        argv     (args/burn-args {:bin bin :source source :out out
                                  :ass-path (.getPath script) :plan plan
                                  :preset (or (:preset opts) args/default-preset)
                                  :threads threads :audio? audio?})]
    (try
      (let [{:keys [exit stderr]} (exec! argv)]
        (when-not (zero? exit)
          ;; The last stderr line rides in the message: the composer boundary
          ;; keeps only class + message of a throw, and that line is the reason.
          (throw (ex-info (str "ffmpeg exited " exit
                               (when-let [reason (last (remove str/blank? (str/split-lines (str stderr))))]
                                 (str ": " (str/trim reason))))
                          {:exit exit
                           :argv argv
                           :stderr (tail (str stderr) stderr-tail-chars)})))
        out)
      (finally
        (.delete script)))))
