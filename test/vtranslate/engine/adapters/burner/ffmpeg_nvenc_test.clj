(ns vtranslate.engine.adapters.burner.ffmpeg-nvenc-test
  "The NVENC burner through the registry and the port, with a reified fake
   process runner: which codec each run names, when a burn falls back to the
   CPU, and when it does not."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-dsl.result :as r]
            [vtranslate.engine.adapters.burner.ffmpeg-nvenc :as sut]
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
  "A 640x360 silent source. A burn on h264_nvenc answers `nvenc-answer`
   ({:exit :stderr}); every other burn succeeds and writes its output."
  [calls nvenc-answer]
  (reify process/IProcessRunner
    (exec! [_ argv]
      (swap! calls conj argv)
      (if (= "h264_nvenc" (codec argv))
        (do (when (zero? (:exit nvenc-answer)) (spit (last argv) "gpu\n"))
            nvenc-answer)
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
  (str (.toFile (Files/createTempDirectory "vt-nvenc" (into-array FileAttribute []))) "/out.mp4.part-1"))

(defn- burns [calls] (filterv codec @calls))

(deftest the-registry-resolves-the-nvenc-burner
  (let [res (reg/resolve-burner :ffmpeg-nvenc {:ffmpeg-bin "/opt/ffmpeg"})]
    (is (r/ok? res))
    (is (satisfies? p.burner/IHardsubBurner (:ok res)))
    (is (some #{:ffmpeg-nvenc} (reg/known)))))

(deftest a-working-gpu-burns-once-on-nvenc
  (let [calls  (atom [])
        burner (sut/make-burner {:process-runner (runner calls {:exit 0 :stderr ""})})
        out    (temp-out)]
    (is (= out (p.burner/burn! burner "/v/in.mp4" out (sample-track) {})))
    (is (= ["h264_nvenc"] (mapv codec (burns calls))))
    (is (= "gpu\n" (slurp out)))
    (is (zero? @(:fallbacks burner)))))

(deftest an-encoder-that-cannot-open-reruns-on-libx264
  (let [calls  (atom [])
        burner (sut/make-burner {:process-runner
                                 (runner calls {:exit 255
                                                :stderr "[h264_nvenc @ 0x1] OpenEncodeSessionEx failed: out of memory (10)\n"})})
        out    (temp-out)]
    (is (= out (p.burner/burn! burner "/v/in.mp4" out (sample-track) {:preset "ultrafast"})))
    (is (= ["h264_nvenc" "libx264"] (mapv codec (burns calls))))
    (is (= "cpu\n" (slurp out)))
    (is (= 1 @(:fallbacks burner)) "the fallback is counted, not silent")
    (testing "the CPU rerun gets the deployment's x264 preset back"
      (let [argv (last (burns calls))]
        (is (= "ultrafast" (nth argv (inc (.indexOf ^java.util.List argv "-preset")))))))))

(deftest a-media-failure-is-rethrown-without-a-cpu-rerun
  (let [calls  (atom [])
        burner (sut/make-burner {:process-runner
                                 (runner calls {:exit 187 :stderr "Error while filtering: no such font\n"})})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no such font"
                          (p.burner/burn! burner "/v/in.mp4" (temp-out) (sample-track) {})))
    (is (= ["h264_nvenc"] (mapv codec (burns calls))))
    (is (zero? @(:fallbacks burner)))))
