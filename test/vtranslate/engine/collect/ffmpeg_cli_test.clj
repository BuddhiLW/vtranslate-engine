(ns vtranslate.engine.collect.ffmpeg-cli-test
  "The system-ffmpeg boundary against a reified fake process runner: what
   argv it runs, what it makes of the answers, and how it fails. No process
   is started, no bytedeco; the real runner has its own test in
   collect.process-test."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [vtranslate.engine.collect.ffmpeg-cli :as sut]
            [vtranslate.engine.collect.process :as process])
  (:import [java.io File IOException]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def ^:private capable-listings
  {:filters " ... subtitles         V->V       Render text subtitles\n TSC overlay VV->V Overlay"
   :encoders " V....D libx264              libx264 H.264\n V....D libopenh264 OpenH264"})

(defn- fake-runner
  "An IProcessRunner that answers like ffmpeg + ffprobe and records every
   argv on `calls`. `answers` overrides: :listings {:filters :encoders},
   :video / :audio (ffprobe CSV lines, \"\" for an absent stream), :exit and
   :stderr for the burn, :missing? to behave like a binary that is not there.
   A successful burn writes its last argument, as ffmpeg would."
  [calls {:keys [listings video audio exit stderr missing?]
          :or   {listings capable-listings
                 video "1920,1080,30/1,3973958" audio "2,133386"
                 exit 0 stderr ""}}]
  (reify process/IProcessRunner
    (exec! [_ argv]
      (swap! calls conj argv)
      (when missing? (throw (IOException. "No such file or directory")))
      ;; Only a burn names an output file; `-version` and the encoder probe
      ;; (output "-", to null) must not leave files in the working directory.
      (when (and (zero? exit) (some #{"-i"} argv) (not= "-" (last argv)))
        (spit (last argv) "encoded\n"))
      {:exit exit :stderr stderr})
    (capture! [_ argv]
      (swap! calls conj argv)
      (when missing? (throw (IOException. "No such file or directory")))
      (let [flags (set argv)]
        {:exit 0
         :out (cond
                (flags "-filters")  (:filters listings)
                (flags "-encoders") (:encoders listings)
                (flags "v:0")       (str video "\n")
                (flags "a:0")       (str audio "\n")
                :else "")}))))

(defn- cli
  ([calls] (cli calls {}))
  ([calls answers] (sut/ffmpeg-cli (fake-runner calls answers) "/opt/ffmpeg/ffmpeg")))

(defn- temp-dir ^File []
  (.toFile (Files/createTempDirectory "vt-ffmpeg-cli" (into-array FileAttribute []))))

(def ^:private cues
  [{:start-ms 0 :end-ms 800 :lines ["hello world"]}
   {:start-ms 800 :end-ms 1600 :lines ["second cue" "two lines"]}])

(deftest capable-needs-a-running-binary-with-libass-and-libx264
  (let [calls (atom [])]
    (is (true? (sut/capable? (cli calls))))
    (is (= [["/opt/ffmpeg/ffmpeg" "-version"]
            ["/opt/ffmpeg/ffmpeg" "-hide_banner" "-filters"]
            ["/opt/ffmpeg/ffmpeg" "-hide_banner" "-encoders"]]
           @calls)
        "one start check and one read per listing"))
  (testing "a missing binary"
    (is (false? (sut/capable? (cli (atom []) {:missing? true})))))
  (testing "a build without the subtitles filter (Homebrew's)"
    (is (false? (sut/capable? (cli (atom []) {:listings (assoc capable-listings :filters " TSC overlay VV->V")})))))
  (testing "a build without libx264"
    (is (false? (sut/capable? (cli (atom []) {:listings (assoc capable-listings :encoders " V....D libopenh264 OpenH264")}))))))

(def ^:private nvenc-listings
  (assoc capable-listings :encoders
         " V....D libx264              libx264 H.264\n V....D h264_nvenc           NVIDIA NVENC H.264 encoder (codec h264)"))

(deftest nvenc-capability-opens-the-encoder-for-real
  (let [calls (atom [])]
    (is (true? (sut/capable? (cli calls {:listings nvenc-listings}) :h264-nvenc)))
    (is (= "h264_nvenc" (nth (last @calls) (inc (.indexOf ^java.util.List (last @calls) "-c:v"))))
        "after the listings, one frame is encoded on the GPU encoder"))
  (testing "listed but unable to open: the distro build on a host without a GPU"
    (let [calls (atom [])
          inner (fake-runner calls {:listings nvenc-listings})
          no-gpu (reify process/IProcessRunner
                   (exec! [_ argv]
                     (if (some #{"lavfi"} argv)
                       (do (swap! calls conj argv)
                           {:exit 255 :stderr "Cannot load libcuda.so.1"})
                       (process/exec! inner argv)))
                   (capture! [_ argv] (process/capture! inner argv)))]
      (is (false? (sut/capable? (sut/ffmpeg-cli no-gpu "/opt/ffmpeg/ffmpeg") :h264-nvenc)))
      (is (some #(some #{"lavfi"} %) @calls) "it got as far as the probe encode")))
  (testing "libx264 never pays for a probe encode"
    (let [calls (atom [])]
      (sut/capable? (cli calls))
      (is (= 3 (count @calls))))))

(deftest burn-with-nvenc-names-the-codec-and-its-preset
  (let [dir   (temp-dir)
        calls (atom [])]
    (sut/burn-hardsub (cli calls) "/v/in.mp4" (str dir "/gpu.part-1") cues
                      {:preset "ultrafast"} :h264-nvenc)
    (let [argv (last @calls)]
      (is (= "h264_nvenc" (nth argv (inc (.indexOf ^java.util.List argv "-c:v")))))
      (is (= "p4" (nth argv (inc (.indexOf ^java.util.List argv "-preset"))))))))

(deftest probe-reads-both-streams-through-the-sibling-ffprobe
  (let [calls (atom [])]
    (is (= {:width 1920 :height 1080 :frame-rate 30.0 :video-bitrate 3973958
            :audio? true :audio-bitrate 133386}
           (sut/probe (cli calls) "/v/in.mp4")))
    (is (every? #(= "/opt/ffmpeg/ffprobe" (first %)) @calls)
        "ffprobe sits beside the ffmpeg that was named"))
  (testing "a silent source"
    (is (= {:audio? false :audio-bitrate nil}
           (select-keys (sut/probe (cli (atom []) {:audio ""}) "/v/in.mp4") [:audio? :audio-bitrate]))))
  (testing "no video stream is a loud failure"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no video stream"
                          (sut/probe (cli (atom []) {:video ""}) "/v/in.mp4")))))

(deftest burn-runs-one-ffmpeg-with-the-script-beside-the-output
  (let [dir   (temp-dir)
        out   (str dir "/out.mp4.part-1")
        calls (atom [])
        res   (sut/burn-hardsub (cli calls) "/v/in.mp4" out cues
                                {:quality :720p :preset "ultrafast" :wrap 6})
        argv  (last @calls)]
    (is (= out res))
    (is (= "encoded\n" (slurp out)) "the runner wrote the output the boundary named")
    (is (= "/opt/ffmpeg/ffmpeg" (first argv)))
    (is (= "/v/in.mp4" (nth argv (inc (.indexOf ^java.util.List argv "-i")))))
    (is (= (str "subtitles=filename=" (str/replace out ":" "\\:") ".ass,scale=1280:720")
           (nth argv (inc (.indexOf ^java.util.List argv "-vf"))))
        "the script sits beside the temp output and the plan's scale follows it")
    (is (= "ultrafast" (nth argv (inc (.indexOf ^java.util.List argv "-preset")))))
    (is (= "libx264" (nth argv (inc (.indexOf ^java.util.List argv "-c:v")))))
    (is (= "aac" (nth argv (inc (.indexOf ^java.util.List argv "-c:a")))))
    (is (= ["-f" "mp4" out] (subvec argv (- (count argv) 3)))
        "the container is named: the temp output has no extension to guess from")
    (is (not (.exists (File. (str out ".ass")))) "the script is removed after the run")))

(deftest burn-drops-audio-for-a-silent-source
  (let [dir   (temp-dir)
        calls (atom [])]
    (sut/burn-hardsub (cli calls {:audio ""}) "/v/in.mp4" (str dir "/silent.part-1") cues {})
    (let [argv (last @calls)]
      (is (some #{"-an"} argv))
      (is (neg? (.indexOf ^java.util.List argv "-c:a"))))))

(deftest burn-raises-with-exit-code-and-stderr-tail
  (let [dir (temp-dir)
        out (str dir "/fail.part-1")
        ex  (is (thrown? clojure.lang.ExceptionInfo
                         (sut/burn-hardsub (cli (atom []) {:exit 187 :stderr "x\nError while filtering: no such font\n"})
                                           "/v/in.mp4" out cues {})))]
    (is (= 187 (:exit (ex-data ex))))
    (is (= "ffmpeg exited 187: Error while filtering: no such font" (ex-message ex))
        "the reason rides in the message, which is all the composer boundary keeps")
    (is (str/includes? (:stderr (ex-data ex)) "no such font"))
    (is (= "/opt/ffmpeg/ffmpeg" (first (:argv (ex-data ex)))))
    (is (not (.exists (File. (str out ".ass")))) "the script is removed on failure too")))
