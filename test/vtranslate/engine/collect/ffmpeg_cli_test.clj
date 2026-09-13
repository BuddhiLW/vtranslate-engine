(ns vtranslate.engine.collect.ffmpeg-cli-test
  "The system-ffmpeg boundary against stub executables: a shell script that
   records its argv and behaves like ffmpeg or ffprobe. No real encoder, no
   bytedeco, so it runs in the default unit suite on any box with /bin/sh."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [vtranslate.engine.collect.ffmpeg-cli :as sut])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir ^File []
  (.toFile (Files/createTempDirectory "vt-ffmpeg-cli" (into-array FileAttribute []))))

(defn- executable! ^File [^File dir name body]
  (let [f (File. dir name)]
    (spit f (str "#!/bin/sh\n" body))
    (.setExecutable f true)
    f))

(defn- stub-pair!
  "ffmpeg + ffprobe stubs in `dir`. The ffmpeg stub appends its argv to
   argv.log, writes stderr, and touches its last argument unless `exit`
   is non-zero. The ffprobe stub answers the video and audio probes."
  [^File dir {:keys [exit stderr video audio filters encoders]
              :or   {exit 0 stderr "" video "1920,1080,30/1,3973958" audio "2,133386"
                     filters " ... subtitles         V->V       Render text subtitles\n TSC overlay VV->V Overlay"
                     encoders " V....D libx264              libx264 H.264\n V....D libopenh264 OpenH264"}}]
  (executable! dir "ffprobe"
               (str "case \"$*\" in *v:0*) printf '%s\\n' '" video "' ;; *a:0*) printf '%s\\n' '" audio "' ;; esac\n"
                    "exit 0\n"))
  (executable! dir "ffmpeg"
               (str "if [ \"$1\" = -version ]; then echo stub; exit 0; fi\n"
                    "case \"$*\" in *-filters*) printf '%s\\n' '" filters "'; exit 0 ;; "
                    "*-encoders*) printf '%s\\n' '" encoders "'; exit 0 ;; esac\n"
                    "printf '%s\\n' \"$@\" > \"" (.getPath dir) "/argv.log\"\n"
                    "for a in \"$@\"; do last=\"$a\"; done\n"
                    "printf '%s' '" stderr "' >&2\n"
                    "if [ " exit " -eq 0 ]; then echo encoded > \"$last\"; fi\n"
                    "exit " exit "\n")))

(defn- argv-log [^File dir]
  (str/split-lines (slurp (File. dir "argv.log"))))

(def ^:private cues
  [{:start-ms 0 :end-ms 800 :lines ["hello world"]}
   {:start-ms 800 :end-ms 1600 :lines ["second cue" "two lines"]}])

(deftest available-is-false-for-a-missing-binary
  (is (false? (sut/available? "/nonexistent/ffmpeg")))
  (is (false? (sut/available? (str (temp-dir) "/not-executable")))))

(deftest available-is-true-when-the-build-has-libass-and-libx264
  (let [dir (temp-dir)]
    (stub-pair! dir {})
    (is (true? (sut/available? (str dir "/ffmpeg"))))))

(deftest available-is-false-for-a-build-missing-the-filter-or-the-encoder
  (testing "no subtitles filter (a Homebrew-style build)"
    (let [dir (temp-dir)]
      (stub-pair! dir {:filters " TSC overlay VV->V Overlay a video source"})
      (is (false? (sut/available? (str dir "/ffmpeg"))))))
  (testing "no libx264, only openh264"
    (let [dir (temp-dir)]
      (stub-pair! dir {:encoders " V....D libopenh264 OpenH264 H.264"})
      (is (false? (sut/available? (str dir "/ffmpeg"))))))
  (testing "a substring is not the filter"
    (let [dir (temp-dir)]
      (stub-pair! dir {:filters " ... subtitles_extra V->V not it"})
      (is (false? (sut/available? (str dir "/ffmpeg")))))))

(deftest probe-reads-both-streams-through-the-sibling-ffprobe
  (let [dir (temp-dir)]
    (stub-pair! dir {})
    (is (= {:width 1920 :height 1080 :frame-rate 30.0 :video-bitrate 3973958
            :audio? true :audio-bitrate 133386}
           (sut/probe (str dir "/ffmpeg") "/v/in.mp4"))))
  (testing "a silent source"
    (let [dir (temp-dir)]
      (stub-pair! dir {:audio ""})
      (is (= {:audio? false :audio-bitrate nil}
             (select-keys (sut/probe (str dir "/ffmpeg") "/v/in.mp4") [:audio? :audio-bitrate])))))
  (testing "no video stream is a loud failure"
    (let [dir (temp-dir)]
      (stub-pair! dir {:video ""})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no video stream"
                            (sut/probe (str dir "/ffmpeg") "/v/in.mp4"))))))

(deftest burn-runs-one-ffmpeg-with-the-script-beside-the-output
  (let [dir  (temp-dir)
        out  (str dir "/out.mp4.part-1")
        _    (stub-pair! dir {})
        res  (sut/burn-hardsub (str dir "/ffmpeg") "/v/in.mp4" out cues
                               {:quality :720p :preset "ultrafast" :wrap 6})
        argv (argv-log dir)]
    (is (= out res))
    (is (= "encoded\n" (slurp out)) "the stub wrote the output the boundary named")
    (is (= "/v/in.mp4" (nth argv (inc (.indexOf argv "-i")))))
    (is (= (str "subtitles=filename=" (str/replace out ":" "\\:") ".ass,scale=1280:720")
           (nth argv (inc (.indexOf argv "-vf"))))
        "the script sits beside the temp output and the plan's scale follows it")
    (is (= "ultrafast" (nth argv (inc (.indexOf argv "-preset")))))
    (is (= "libx264" (nth argv (inc (.indexOf argv "-c:v")))))
    (is (= "aac" (nth argv (inc (.indexOf argv "-c:a")))))
    (is (not (.exists (File. (str out ".ass")))) "the script is removed after the run")))

(deftest burn-drops-audio-for-a-silent-source
  (let [dir (temp-dir)
        out (str dir "/silent.mp4.part-1")]
    (stub-pair! dir {:audio ""})
    (sut/burn-hardsub (str dir "/ffmpeg") "/v/in.mp4" out cues {})
    (let [argv (argv-log dir)]
      (is (some #{"-an"} argv))
      (is (neg? (.indexOf argv "-c:a"))))))

(deftest burn-raises-with-exit-code-and-stderr-tail
  (let [dir (temp-dir)
        out (str dir "/fail.mp4.part-1")]
    (stub-pair! dir {:exit 187 :stderr "Error while filtering: no such font"})
    (let [ex (is (thrown? clojure.lang.ExceptionInfo
                          (sut/burn-hardsub (str dir "/ffmpeg") "/v/in.mp4" out cues {})))]
      (is (= 187 (:exit (ex-data ex))))
      (is (= "ffmpeg exited 187: Error while filtering: no such font" (ex-message ex))
          "the reason rides in the message, which is all the composer boundary keeps")
      (is (str/includes? (:stderr (ex-data ex)) "no such font"))
      (is (= (str dir "/ffmpeg") (first (:argv (ex-data ex)))))
      (is (not (.exists (File. (str out ".ass")))) "the script is removed on failure too"))))
