(ns vtranslate.engine.api-compose-ingress-test
  "End to end over api/run-compose-job, the burn-only ingress: the real
   filesystem reader and the real codec parser, with a recording composer stub
   in place of ffmpeg. What this has to prove is mostly NEGATIVE - the ingress
   is constructible with no transcriber and no translator, which is what lets a
   worker that is not trusted with plaintext or provider keys run the burn."
  (:require [clojure.test :refer [deftest testing is]]
            [hive-dsl.result :as r]
            [vtranslate.engine.api :as api]
            [vtranslate.engine.adapters.source.file :as file]
            [vtranslate.engine.adapters.codec.dispatch :as dispatch]
            [vtranslate.engine.port.composer :as p.comp])
  (:import [java.io File]))

(def ^:private srt-2cues
  (str "1\n00:00:01,000 --> 00:00:04,000\nHello world\n\n"
       "2\n00:00:05,000 --> 00:00:08,000\nSecond cue\n"))

(defn- write-temp [content suffix]
  (let [f (doto (File/createTempFile "vt-compose" suffix) (.deleteOnExit))]
    (spit f content)
    (.getPath f)))

(defn- recording-composer
  "IVideoComposer stub recording what the burn was asked to do."
  [calls]
  (reify p.comp/IVideoComposer
    (compose [_ video-source track opts]
      (swap! calls conj {:source     video-source
                         :cues       (count (:cues track))
                         :output-uri (:output-uri opts)
                         :watermark? (:watermark? opts)
                         :quality    (:quality opts)})
      (r/ok {:output-uri (or (:output-uri opts) "/tmp/default.subbed.mp4")}))))

(defn- ports
  "Everything the burn needs, and deliberately nothing else: no :transcriber,
   no :translator, no :segmenter."
  [calls]
  {:source (file/make-source-reader)
   :parser (dispatch/make-codec)
   :muxer  (recording-composer calls)})

(deftest the-burn-only-ingress-needs-no-transcriber-and-no-translator
  (let [calls (atom [])
        subs  (write-temp srt-2cues ".srt")
        res   (api/run-compose-job
               (ports calls)
               {:job-id "j-burn" :source "/media/v.mp4" :subtitle subs
                :target-language "pt-BR" :format :format/srt
                :output "/out/v.pt-BR.mp4" :quality :quality/high
                :watermark? true})]
    (is (r/ok? res))
    (is (= :job/completed (get-in res [:ok :job :state :adt/variant])))
    (is (= "/out/v.pt-BR.mp4" (get-in res [:ok :output-video])))
    (is (= 1 (count (get-in res [:ok :outputs]))) "burning is one language at a time")
    (testing "the composer got the parsed cues and the job's own burn options"
      (is (= 1 (count @calls)))
      (let [c (first @calls)]
        (is (= "/media/v.mp4" (:source c)) "the video is the source, not the subtitle")
        (is (= 2 (:cues c)))
        (is (= "/out/v.pt-BR.mp4" (:output-uri c)))
        (is (true? (:watermark? c)))
        (is (= :quality/high (:quality c)))))))

(deftest a-subtitle-with-no-cues-fails-loud
  (let [calls (atom [])
        res   (api/run-compose-job (ports calls)
                                   {:job-id "j-empty" :source "/media/v.mp4"
                                    :subtitle (write-temp "" ".srt")
                                    :target-language "pt-BR"})]
    (is (not (r/ok? res)))
    (is (empty? @calls) "nothing is burned when there is nothing to burn")))

(deftest an-unreadable-subtitle-fails-before-the-burn
  (let [calls (atom [])
        res   (api/run-compose-job (ports calls)
                                   {:job-id "j-missing" :source "/media/v.mp4"
                                    :subtitle "/nope/does-not-exist.srt"
                                    :target-language "pt-BR"})]
    (is (not (r/ok? res)))
    (is (empty? @calls))))
