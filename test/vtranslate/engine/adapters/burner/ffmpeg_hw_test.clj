(ns vtranslate.engine.adapters.burner.ffmpeg-hw-test
  "The one config-driven hardware burner through the registry and the port,
   with a reified fake process runner injected at the :process-runner port.
   No with-redefs: the runner is a dependency the burner already takes, so
   the test hands one in rather than patching a concretion.

   What is asserted: each registered backend key names its own encoder, the
   VAAPI argv carries the device and the upload, a device that cannot open
   falls back to libx264 exactly as NVENC always did, and an encoder no
   table knows is refused at WIRING rather than per burn."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-dsl.result :as r]
            [vtranslate.engine.adapters.burner.ffmpeg-hw :as sut]
            [vtranslate.engine.collect.process :as process]
            [vtranslate.engine.domain.rendering :as rd]
            [vtranslate.engine.port.burner :as p.burner]
            [vtranslate.engine.providers.burner-registry :as reg])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- codec [argv]
  (let [i (.indexOf ^java.util.List argv "-c:v")]
    (when-not (neg? i) (nth argv (inc i)))))

(defn- runner
  "A 640x360 silent source. A burn whose codec is `hw-codec` answers
   `hw-answer` ({:exit :stderr}); every other burn succeeds."
  [calls hw-codec hw-answer]
  (reify process/IProcessRunner
    (exec! [_ argv]
      (swap! calls conj argv)
      (if (= hw-codec (codec argv))
        (do (when (zero? (:exit hw-answer)) (spit (last argv) "gpu\n"))
            hw-answer)
        (do (spit (last argv) "cpu\n")
            {:exit 0 :stderr ""})))
    (capture! [_ argv]
      (swap! calls conj argv)
      {:exit 0 :out (if (some #{"v:0"} argv) "640,360,25/1,N/A\n" "")})))

(defn- sample-track []
  (let [t  (:ok (rd/make-subtitle-track {:id "s" :source-id "c" :language "pt-BR" :format :format/srt}))
        c1 (:ok (rd/make-cue {:index 1 :start-ms 0 :end-ms 800 :lines ["hello world"]}))]
    (:ok (rd/render (rd/add-cue t c1)))))

(defn- temp-out []
  (str (.toFile (Files/createTempDirectory "vt-hw" (into-array FileAttribute []))) "/out.mp4.part-1"))

(defn- burns [calls] (filterv codec @calls))

(deftest both-hardware-backends-resolve-through-one-implementation
  (testing "the registry knows each key, and each builds the SAME record type"
    (doseq [k [:ffmpeg-nvenc :ffmpeg-vaapi]]
      (let [res (reg/resolve-burner k {:ffmpeg-bin "/opt/ffmpeg"})]
        (is (r/ok? res) (str k " resolves"))
        (is (satisfies? p.burner/IHardsubBurner (:ok res)))
        (is (instance? vtranslate.engine.adapters.burner.ffmpeg_hw.FfmpegHwBurner (:ok res))
            (str k " is the one generic burner, not a bespoke record"))
        (is (some #{k} (reg/known))))))
  (testing "and each carries its own encoder, which is the only difference"
    (is (= :h264-nvenc (:encoder (:ok (reg/resolve-burner :ffmpeg-nvenc {})))))
    (is (= :h264-vaapi (:encoder (:ok (reg/resolve-burner :ffmpeg-vaapi {})))))))

(deftest a-working-vaapi-device-burns-once-with-the-device-and-the-upload
  (let [calls  (atom [])
        burner (:ok (reg/resolve-burner
                     :ffmpeg-vaapi
                     {:process-runner (runner calls "h264_vaapi" {:exit 0 :stderr ""})}))
        out    (temp-out)]
    (is (= out (p.burner/burn! burner "/v/in.mp4" out (sample-track) {})))
    (is (= ["h264_vaapi"] (mapv codec (burns calls))))
    (is (= "gpu\n" (slurp out)))
    (is (zero? @(:fallbacks burner)))
    (let [argv (first (burns calls))]
      (is (some #{"-vaapi_device"} argv) "the device opens")
      (is (not-any? #{"-preset"} argv) "h264_vaapi takes none")
      (is (not-any? #{"-pix_fmt"} argv) "it consumes its own surfaces")
      (is (some #(and (string? %) (.endsWith ^String % "format=nv12,hwupload")) argv)
          "the graph uploads the frame"))))

(deftest a-device-that-cannot-open-reruns-on-libx264-and-counts-it
  (let [calls  (atom [])
        burner (:ok (reg/resolve-burner
                     :ffmpeg-vaapi
                     {:process-runner (runner calls "h264_vaapi"
                                              {:exit 255
                                               :stderr "[AVHWDeviceContext @ 0x1] Failed to initialise VAAPI connection: -1 (unknown libva error).\n"})}))
        out    (temp-out)]
    (is (= out (p.burner/burn! burner "/v/in.mp4" out (sample-track) {:preset "ultrafast"})))
    (is (= ["h264_vaapi" "libx264"] (mapv codec (burns calls))))
    (is (= "cpu\n" (slurp out)))
    (is (= 1 @(:fallbacks burner)) "the fallback is counted, not silent")
    (testing "the CPU rerun gets the deployment's x264 preset and its pix_fmt back"
      (let [argv (last (burns calls))]
        (is (= "ultrafast" (nth argv (inc (.indexOf ^java.util.List argv "-preset")))))
        (is (some #{"-pix_fmt"} argv))
        (is (not-any? #{"-vaapi_device"} argv) "and no device it does not use")))))

(deftest a-media-failure-is-rethrown-without-a-cpu-rerun
  (let [calls  (atom [])
        burner (:ok (reg/resolve-burner
                     :ffmpeg-vaapi
                     {:process-runner (runner calls "h264_vaapi"
                                              {:exit 187 :stderr "Error while filtering: no such font\n"})}))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no such font"
                          (p.burner/burn! burner "/v/in.mp4" (temp-out) (sample-track) {})))
    (is (= ["h264_vaapi"] (mapv codec (burns calls))))
    (is (zero? @(:fallbacks burner)))))

(deftest config-names-the-encoder-directly-for-hardware-with-no-alias
  (testing ":burn-encoder reaches a row a deployment supplied, with no release"
    (let [calls  (atom [])
          opts   {:process-runner (runner calls "h264_qsv" {:exit 0 :stderr ""})
                  :burn-encoder :h264-qsv
                  :qsv-preset "medium"
                  :encoders {:h264-qsv {:codec "h264_qsv" :preset-key :qsv-preset
                                        :pix-fmt "nv12" :hardware? true}}}
          burner (:ok (reg/resolve-burner :ffmpeg-vaapi opts))
          out    (temp-out)]
      (is (= :h264-qsv (:encoder burner)))
      (is (= out (p.burner/burn! burner "/v/in.mp4" out (sample-track) opts)))
      (is (= ["h264_qsv"] (mapv codec (burns calls)))))))

(deftest an-encoder-no-table-knows-is-refused-at-wiring
  (testing "before a single job is accepted, not on every burn forever"
    (let [res (reg/resolve-burner :ffmpeg-vaapi {:burn-encoder :h264-invented})]
      (is (r/err? res))
      (is (= :error/unknown-encoder (:error res)))
      (is (= :h264-invented (:encoder res)))
      (is (= :ffmpeg-vaapi (:backend res))))))
