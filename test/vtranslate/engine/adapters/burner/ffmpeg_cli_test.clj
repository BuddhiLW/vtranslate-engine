(ns vtranslate.engine.adapters.burner.ffmpeg-cli-test
  "The ffmpeg CLI burner through the registry and the port, with a reified
   fake process runner handed in through :composer-opts. No process, no
   bytedeco: this is the one burner the default classpath can exercise."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-dsl.result :as r]
            [vtranslate.engine.adapters.burner.ffmpeg-cli :as sut]
            [vtranslate.engine.collect.process :as process]
            [vtranslate.engine.domain.rendering :as rd]
            [vtranslate.engine.port.burner :as p.burner]
            [vtranslate.engine.providers.burner-registry :as reg])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- recording-runner
  "Answers every ffprobe question with a 640x360 silent source and every
   ffmpeg run with success, recording the argv."
  [calls]
  (reify process/IProcessRunner
    (exec! [_ argv]
      (swap! calls conj argv)
      (spit (last argv) "encoded\n")
      {:exit 0 :stderr ""})
    (capture! [_ argv]
      (swap! calls conj argv)
      {:exit 0 :out (cond (some #{"v:0"} argv) "640,360,25/1,N/A\n"
                          (some #{"a:0"} argv) ""
                          :else "")})))

(defn- sample-track []
  (let [t  (:ok (rd/make-subtitle-track {:id "s" :source-id "c" :language "pt-BR" :format :format/srt}))
        c1 (:ok (rd/make-cue {:index 1 :start-ms 0 :end-ms 800 :lines ["hello world"]}))]
    (:ok (rd/render (rd/add-cue t c1)))))

(defn- temp-out []
  (str (.toFile (Files/createTempDirectory "vt-burner" (into-array FileAttribute []))) "/out.mp4.part-1"))

(deftest the-registry-resolves-the-cli-burner-from-composer-opts
  (let [calls (atom [])
        res   (reg/resolve-burner :ffmpeg-cli {:ffmpeg-bin "/opt/ffmpeg" :process-runner (recording-runner calls)})]
    (is (r/ok? res))
    (is (satisfies? p.burner/IHardsubBurner (:ok res)))
    (is (= "/opt/ffmpeg" (get-in (:ok res) [:cli :bin])))
    (is (some #{:ffmpeg-cli} (reg/known)))))

(deftest an-unregistered-backend-fails-loud-with-the-known-set
  (let [res (reg/resolve-burner :nvenc {})]
    (is (r/err? res))
    (is (= :error/unknown-burner (:error res)))
    (is (= :nvenc (:provider-key res)))
    (is (some #{:ffmpeg-cli} (:known res)))))

(deftest burn-through-the-port-runs-ffmpeg-on-the-track
  (let [calls  (atom [])
        burner (sut/make-burner {:ffmpeg-bin "/opt/ffmpeg" :process-runner (recording-runner calls)})
        out    (temp-out)]
    (is (= out (p.burner/burn! burner "/v/in.mp4" out (sample-track) {:quality :source})))
    (let [argv (last @calls)]
      (is (= "/opt/ffmpeg" (first argv)))
      (is (= out (last argv)))
      (testing "a silent 360p source: no audio mapped, no scaling, estimated bitrate"
        (is (some #{"-an"} argv))
        (is (not-any? #(re-find #"scale=" %) argv))
        (is (= "576000" (nth argv (inc (.indexOf ^java.util.List argv "-b:v"))))
            "0.10 bits per pixel per frame at 640x360x25 when the source reports none")))))
