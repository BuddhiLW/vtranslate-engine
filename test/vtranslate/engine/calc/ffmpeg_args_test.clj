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

(deftest burn-args-with-nvenc-keeps-the-layout-and-swaps-the-codec
  (let [argv (sut/burn-args {:source "s" :out "o" :ass-path "a" :plan plan-1080
                             :encoder :h264-nvenc :threads 3})]
    (is (= ["-c:v" "h264_nvenc" "-preset" "p4" "-b:v" "3973958" "-pix_fmt" "yuv420p" "-threads" "3"]
           (subvec argv 10 20))
        "NVENC's own default preset, never x264's"))
  (testing "an unknown encoder is refused, not quietly encoded on libx264"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown encoder"
                          (sut/burn-args {:source "s" :out "o" :ass-path "a"
                                          :plan plan-1080 :encoder :nope})))))

(defn- flag
  "The value ffmpeg would read for `f`, or nil when the flag is absent."
  [argv f]
  (let [i (.indexOf ^java.util.List argv f)]
    (when-not (neg? i) (nth argv (inc i)))))

(deftest a-vaapi-burn-omits-what-h264-vaapi-rejects-and-adds-what-it-needs
  (let [argv (sut/burn-args {:source "/v/in.mp4" :out "/v/o.part" :ass-path "/v/o.ass"
                             :plan plan-1080 :encoder :h264-vaapi :threads 3})]
    (is (= "h264_vaapi" (flag argv "-c:v")))
    (is (nil? (flag argv "-preset"))
        "h264_vaapi rejects -preset as an unrecognised option and dies at argv parse")
    (is (nil? (flag argv "-pix_fmt"))
        "the encoder consumes VAAPI surfaces, so yuv420p must not be forced")
    (testing "the device opens BEFORE the input"
      (is (= "/dev/dri/renderD128" (flag argv "-vaapi_device")))
      (is (< (.indexOf ^java.util.List argv "-vaapi_device")
             (.indexOf ^java.util.List argv "-i"))))
    (testing "the graph ends by uploading the frame to the device"
      (is (= "subtitles=filename=/v/o.ass,format=nv12,hwupload" (flag argv "-vf"))))
    (is (= "3973958" (flag argv "-b:v")) "everything else is unchanged")
    (is (= "3" (flag argv "-threads")))))

(deftest the-filter-suffix-reaches-the-watermark-graph-too
  (let [mark {:png "/v/mark.png" :position ["W-w-24" "H-h-24"]}
        vaapi (sut/burn-args {:source "s" :out "o" :ass-path "/v/o.ass" :plan plan-1080
                              :encoder :h264-vaapi :watermark mark})
        x264  (sut/burn-args {:source "s" :out "o" :ass-path "/v/o.ass" :plan plan-1080
                              :watermark mark})]
    (is (= (str "[0:v]subtitles=filename=/v/o.ass[subbed];"
                "[subbed][1:v]overlay=W-w-24:H-h-24,format=nv12,hwupload[vid]")
           (flag vaapi "-filter_complex"))
        "the upload lands after the overlay: overlay cannot read a hardware surface")
    (is (= (str "[0:v]subtitles=filename=/v/o.ass[subbed];"
                "[subbed][1:v]overlay=W-w-24:H-h-24[vid]")
           (flag x264 "-filter_complex"))
        "an encoder with no suffix emits the graph it always did")
    (is (= "[vid]" (flag vaapi "-map")))))

(deftest encoder-rows-are-config-and-built-ins-are-only-defaults
  (testing "a deployment describes hardware this release has never heard of"
    (let [opts {:encoders {:h264-qsv {:codec "h264_qsv" :preset-key :qsv-preset
                                      :default-preset "medium" :pix-fmt "nv12"
                                      :hardware? true}}}
          argv (sut/burn-args {:source "s" :out "o" :ass-path "a" :plan plan-1080
                               :encoder :h264-qsv :encoders (sut/encoders-from opts)
                               :preset (sut/encoder-preset :h264-qsv opts)})]
      (is (= "h264_qsv" (flag argv "-c:v")))
      (is (= "medium" (flag argv "-preset")))
      (is (= "nv12" (flag argv "-pix_fmt")))))
  (testing "built-ins survive a config that adds a row"
    (let [table (sut/encoders-from {:encoders {:h264-qsv {:codec "h264_qsv"}}})]
      (is (= "libx264" (:codec (sut/encoder-spec table :libx264))))
      (is (= "h264_vaapi" (:codec (sut/encoder-spec table :h264-vaapi))))))
  (testing "a config row is merged OVER the built-in, field by field"
    (let [table (sut/encoders-from {:encoders {:h264-vaapi {:device-args ["-vaapi_device" "/dev/dri/renderD129"]}}})
          row   (sut/encoder-spec table :h264-vaapi)]
      (is (= ["-vaapi_device" "/dev/dri/renderD129"] (:device-args row)) "overridden")
      (is (= "h264_vaapi" (:codec row)) "and the rest of the row kept")
      (is (= "format=nv12,hwupload" (:filter-suffix row)))))
  (testing "config can retire a field it does not want"
    (let [table (sut/encoders-from {:encoders {:libx264 {:default-preset nil}}})]
      (is (nil? (sut/encoder-preset :libx264 {:encoders {:libx264 {:default-preset nil}}}))
          "no preset means burn-args emits no -preset")
      (is (= "libx264" (:codec (sut/encoder-spec table :libx264)))))))

(deftest each-encoder-reads-its-own-preset-key
  (let [opts {:preset "ultrafast" :nvenc-preset "p1"}]
    (is (= "ultrafast" (sut/encoder-preset :libx264 opts)))
    (is (= "p1" (sut/encoder-preset :h264-nvenc opts))))
  (is (= "p4" (sut/encoder-preset :h264-nvenc {:preset "veryfast"}))
      "a deployment's x264 preset does not leak into NVENC, which refuses it")
  (is (= sut/default-preset (sut/encoder-preset :libx264 {})))
  (is (nil? (sut/encoder-preset :h264-vaapi {:preset "veryfast" :nvenc-preset "p1"}))
      "h264_vaapi has no preset vocabulary at all, so no other encoder's leaks in"))

(deftest hardware-capability-is-the-listing-plus-a-real-open
  (let [filters  " ... subtitles         V->V       Render text subtitles"
        encoders " V....D libx264  libx264 H.264\n V....D h264_nvenc NVIDIA NVENC H.264 encoder (codec h264)"]
    (is (true? (sut/capable? {:filters filters :encoders encoders} :h264-nvenc)))
    (is (false? (sut/capable? {:filters filters :encoders " V....D libx264 libx264"} :h264-nvenc))))
  (is (= ["-c:v" "h264_nvenc" "-f" "null" "-"]
         (take-last 5 (sut/encoder-probe-args {:bin "/usr/bin/ffmpeg" :encoder :h264-nvenc}))))
  (is (true? (:hardware? (sut/encoder-spec :h264-nvenc))))
  (is (true? (:hardware? (sut/encoder-spec :h264-vaapi))))
  (is (not (:hardware? (sut/encoder-spec :libx264))))
  (testing "a VAAPI probe opens the device and uploads, or it fails for a
            reason that says nothing about the hardware"
    (let [argv (sut/encoder-probe-args {:encoder :h264-vaapi})]
      (is (= ["-c:v" "h264_vaapi" "-f" "null" "-"] (take-last 5 argv)))
      (is (some #{"-vaapi_device"} argv))
      (is (= "format=nv12,hwupload" (nth argv (inc (.indexOf ^java.util.List argv "-vf")))))))
  (testing "a VAAPI device that cannot be opened is a hardware failure, the
            same class as a taken NVENC session, so the CPU rerun applies"
    (is (true? (sut/hardware-unavailable? "[AVHWDeviceContext @ 0x1] Failed to initialise VAAPI connection: -1 (unknown libva error).")))
    (is (true? (sut/hardware-unavailable? "Device creation failed: -5.")))
    (is (true? (sut/hardware-unavailable? "[h264_vaapi @ 0x1] No usable encoding profile found.")))))

(deftest only-an-encoder-that-cannot-open-is-a-hardware-failure
  (testing "stderr observed 2026-09-15 on a host with no GPU (distro ffmpeg 6.1.1)"
    (is (true? (sut/hardware-unavailable?
                "[h264_nvenc @ 0x5b34] Cannot load libcuda.so.1\n[vost#0:0/h264_nvenc @ 0x5b34] Error while opening encoder - maybe incorrect parameters such as bit_rate, rate, width or height.\nError while filtering: Operation not permitted\n"))))
  (is (true? (sut/hardware-unavailable? "[h264_nvenc @ 0x1] OpenEncodeSessionEx failed: incompatible client key (21): (no details)")))
  (is (true? (sut/hardware-unavailable? "[h264_nvenc @ 0x1] No capable devices found")))
  (is (false? (sut/hardware-unavailable? "x\nError while filtering: no such font\n"))
      "a media or filtergraph failure would fail libx264 the same way")
  (is (false? (sut/hardware-unavailable? nil))))

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
