(ns vtranslate.engine.calc.ffmpeg-args-test
  "Pure argv calc for the system ffmpeg: filter escaping, the burn command
   line, probe lines in and out. No process is run."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [vtranslate.engine.calc.ffmpeg-args :as sut]))

(deftest filter-escape-survives-both-parsers
  (is (= "/tmp/plain.ass" (sut/filter-escape "/tmp/plain.ass")))
  (is (= "C\\:\\\\dir\\\\a\\,b\\;c\\[d\\]\\'e.ass"
         (sut/filter-escape "C:\\dir\\a,b;c[d]'e.ass"))))

(def ^:private plan-1080
  {:width 1920 :height 1080 :rescaled? false
   :video-bitrate 3973958 :audio-bitrate 133386})

(deftest video-filter-scales-after-the-subtitles-only-when-asked
  (is (= "subtitles=filename=/v/out.mp4.ass"
         (sut/video-filter plan-1080 "/v/out.mp4.ass")))
  (is (= "subtitles=filename=/v/out.mp4.ass,scale=1280:720"
         (sut/video-filter (assoc plan-1080 :width 1280 :height 720 :rescaled? true)
                           "/v/out.mp4.ass"))))

(deftest burn-args-is-one-libx264-process
  (let [argv (sut/burn-args {:bin "/usr/bin/ffmpeg" :source "/v/in.mp4" :out "/v/out.part"
                             :ass-path "/v/out.part.ass" :plan plan-1080
                             :preset "ultrafast" :threads 3 :audio? true})]
    (is (= "/usr/bin/ffmpeg" (first argv)))
    (is (= "/v/out.part" (last argv)) "the output is the final argument")
    (is (= ["-i" "/v/in.mp4"] (subvec argv 6 8)))
    (is (= ["-c:v" "libx264" "-preset" "ultrafast" "-b:v" "3973958" "-pix_fmt" "yuv420p" "-threads" "3"]
           (subvec argv 10 20)))
    (is (= ["-c:a" "aac" "-b:a" "133386"] (subvec argv 20 24)))
    (is (some #{"-nostdin"} argv) "never waits on a terminal")
    (is (= ["-movflags" "+faststart" "-f" "mp4"] (subvec argv 24 28))
        "the container is named, since the temp output has no extension to guess from"))
  (testing "a silent source drops audio instead of mapping a stream that is not there"
    (let [argv (sut/burn-args {:source "s" :out "o" :ass-path "a" :plan plan-1080 :audio? false})]
      (is (some #{"-an"} argv))
      (is (not-any? #{"aac"} argv))))
  (testing "defaults"
    (let [argv (sut/burn-args {:source "s" :out "o" :ass-path "a" :plan plan-1080})]
      (is (= "ffmpeg" (first argv)))
      (is (= ["-preset" sut/default-preset] (subvec argv 12 14)))
      (is (= ["-threads" "1"] (subvec argv 18 20))))))

(deftest probe-args-select-one-stream-as-csv
  (is (= ["ffprobe" "-v" "error" "-select_streams" "v:0"
          "-show_entries" "stream=width,height,r_frame_rate,bit_rate" "-of" "csv=p=0" "/v/in.mp4"]
         (sut/probe-args {:source "/v/in.mp4" :kind :video})))
  (is (= ["/opt/ffprobe" "-v" "error" "-select_streams" "a:0"
          "-show_entries" "stream=channels,bit_rate" "-of" "csv=p=0" "/v/in.mp4"]
         (sut/probe-args {:bin "/opt/ffprobe" :source "/v/in.mp4" :kind :audio}))))

(deftest probe-lines-parse-in-ffprobe-section-order-and-tolerate-na
  (is (= {:width 1920 :height 1080 :frame-rate 30.0 :video-bitrate 3973958}
         (sut/parse-video-probe "1920,1080,30/1,3973958\n")))
  (is (= {:width 1280 :height 720 :frame-rate (/ 30000.0 1001.0) :video-bitrate nil}
         (sut/parse-video-probe "1280,720,30000/1001,N/A")))
  (is (nil? (sut/parse-video-probe "")) "no video stream")
  (is (nil? (sut/parse-video-probe "0,0,30/1,1")) "unreadable dimensions")
  (is (= {:audio-bitrate 133386 :channels 2} (sut/parse-audio-probe "2,133386"))
      "ffprobe prints channels before bit_rate whatever order was asked")
  (is (= {:audio-bitrate nil :channels 1} (sut/parse-audio-probe "1,N/A")))
  (is (nil? (sut/parse-audio-probe nil)) "no audio stream"))

(deftest capability-is-read-off-the-listings-as-whole-names
  (let [filters  " ... subtitles         V->V       Render text subtitles\n TSC overlay VV->V Overlay"
        encoders " V....D libx264              libx264 H.264\n V....D libopenh264 OpenH264"]
    (is (true? (sut/lists-name? filters "subtitles")))
    (is (false? (sut/lists-name? " ... subtitles_extra V->V not it" "subtitles"))
        "a longer name that starts the same is not the filter")
    (is (false? (sut/lists-name? nil "subtitles")))
    (is (true? (sut/capable? {:filters filters :encoders encoders})))
    (is (false? (sut/capable? {:filters " TSC overlay VV->V" :encoders encoders})) "no libass")
    (is (false? (sut/capable? {:filters filters :encoders " V....D libopenh264 OpenH264"})) "no x264")
    (is (false? (sut/capable? {:filters filters})) "a listing the binary never gave")
    (is (= ["/usr/bin/ffmpeg" "-hide_banner" "-filters"]
           (sut/listing-args {:bin "/usr/bin/ffmpeg" :listing :filters})))
    (is (= ["ffmpeg" "-hide_banner" "-encoders"] (sut/listing-args {:listing :encoders})))))

(deftest probe-binary-sits-beside-ffmpeg
  (is (= "ffprobe" (sut/probe-binary "ffmpeg")))
  (is (= "/usr/bin/ffprobe" (sut/probe-binary "/usr/bin/ffmpeg")))
  (is (= "/opt/ffmpeg-7/bin/ffprobe.exe" (sut/probe-binary "/opt/ffmpeg-7/bin/ffmpeg.exe")))
  (is (= "ffprobe" (sut/probe-binary "/weird/encoder")) "a name without ffmpeg in it falls back to PATH"))

(defspec escaped-path-round-trips-through-both-unescapes 100
  (prop/for-all [s (gen/fmap #(apply str %) (gen/vector (gen/elements "ab/:\\',;[]. ") 0 20))]
    (let [once (fn [t] (str/replace t #"\\(.)" "$1"))]
      (= s (once (sut/filter-escape s))))))
