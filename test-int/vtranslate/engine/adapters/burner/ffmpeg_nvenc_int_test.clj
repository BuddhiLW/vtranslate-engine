(ns vtranslate.engine.adapters.burner.ffmpeg-nvenc-int-test
  "The NVENC burner against a real ffmpeg on a real NVIDIA GPU: the capability
   probe opens the encoder, and a captioned burn comes back as H.264 without
   falling back to the CPU.

   OPT-IN. Needs an ffmpeg (with ffprobe beside it) that can open h264_nvenc:

     VT_NVENC_FFMPEG=/usr/bin/ffmpeg clojure -M:dev:test:itest:ffmpeg \\
       --focus vtranslate.engine.adapters.burner.ffmpeg-nvenc-int-test"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [vtranslate.engine.adapters.burner.ffmpeg-nvenc :as sut]
            [vtranslate.engine.calc.ffmpeg-args :as args]
            [vtranslate.engine.collect.ffmpeg-cli :as cli]
            [vtranslate.engine.collect.process :as process]
            [vtranslate.engine.domain.rendering :as rd]
            [vtranslate.engine.port.burner :as p.burner])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def ffmpeg (System/getenv "VT_NVENC_FFMPEG"))

(defn- temp-dir []
  (.toFile (Files/createTempDirectory "vt-nvenc-int" (into-array FileAttribute []))))

(defn- track []
  (let [t  (:ok (rd/make-subtitle-track {:id "s" :source-id "c" :language "pt-BR" :format :format/srt}))
        c1 (:ok (rd/make-cue {:index 1 :start-ms 0 :end-ms 1500 :lines ["olá mundo"]}))
        c2 (:ok (rd/make-cue {:index 2 :start-ms 1500 :end-ms 3000 :lines ["segunda legenda"]}))]
    (:ok (rd/render (-> t (rd/add-cue c1) (rd/add-cue c2))))))

(defn- make-source!
  "A 3 s 1280x720 clip with a tone, encoded on the CPU so the source does not
   depend on the thing under test."
  [path]
  (let [{:keys [exit stderr]}
        (process/exec! process/system-runner
                       [ffmpeg "-y" "-nostdin" "-hide_banner" "-loglevel" "error"
                        "-f" "lavfi" "-i" "testsrc2=size=1280x720:rate=25:duration=3"
                        "-f" "lavfi" "-i" "sine=frequency=440:duration=3"
                        "-c:v" "libx264" "-preset" "ultrafast" "-c:a" "aac" "-shortest"
                        "-f" "mp4" (str path)])]
    (is (zero? exit) stderr)))

(deftest a-captioned-burn-runs-on-the-gpu
  (if-not ffmpeg
    (is (nil? ffmpeg) "set VT_NVENC_FFMPEG to run the NVENC integration test")
    (let [dir    (temp-dir)
          source (io/file dir "in.mp4")
          out    (str (io/file dir "out.mp4.part-1"))
          opts   {:ffmpeg-bin ffmpeg}
          burner (sut/make-burner opts)]
      (testing "the probe opens the encoder, not just reads the listing"
        (is (true? (cli/capable? (:cli burner) :h264-nvenc))))
      (make-source! source)
      (is (= out (p.burner/burn! burner (str source) out (track) {:quality :source})))
      (is (zero? @(:fallbacks burner)) "the burn ran on NVENC, not the CPU rerun")
      (let [probe (process/output-of process/system-runner
                                     [(args/probe-binary ffmpeg) "-v" "error" "-select_streams" "v:0"
                                      "-show_entries" "stream=codec_name,width,height" "-of" "csv=p=0" out])]
        (is (= "h264,1280,720" (some-> probe str/trim)))))))
