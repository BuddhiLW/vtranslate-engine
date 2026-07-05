(ns vtranslate.engine.adapters.codec.dispatch-test
  "Registry-backed dispatch codec — ONE adapter that satisfies both subtitle
   ports and selects the concrete SRT/VTT codec per call: `render-bytes` by the
   track's :format, `parse` by the requested format arg. Covers the happy path
   for each direction, the :error/unsupported-format railway for an unregistered
   format, and a render⇄parse round-trip that preserves the cue count."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [hive-dsl.result :as r]
            [vtranslate.engine.adapters.codec.dispatch :as dispatch]
            [vtranslate.engine.domain.rendering :as rd]
            [vtranslate.engine.port.subtitle :as p.sub]))

(def codec (dispatch/make-codec))

(def cue-specs
  "Two boundary cues shared by the render + round-trip cases."
  [{:index 1 :start-ms 0    :end-ms 1500 :lines ["Hello world"]}
   {:index 2 :start-ms 1500 :end-ms 3200 :lines ["Second cue" "line two"]}])

(defn- build-track
  "Assemble a rendered-shape SubtitleTrack of `cue-specs` in `format`, threading
   the Result-returning smart ctors (make-subtitle-track + make-cue) with r/let-ok;
   add-cue appends each validated cue. => (r/ok SubtitleTrack) | (r/err ...)."
  [format cue-specs]
  (r/let-ok [track (rd/make-subtitle-track
                    {:id "t1" :source-id "src" :language "en" :format format})]
    (reduce (fn [acc spec]
              (r/let-ok [acc acc
                         cue (rd/make-cue spec)]
                (r/ok (rd/add-cue acc cue))))
            (r/ok track)
            cue-specs)))

;; --- parse: dispatch selects the codec by the requested format arg ----------

(def srt-text
  (str "1\n00:00:01,000 --> 00:00:04,000\nHello world\n\n"
       "2\n00:00:05,000 --> 00:00:08,000\nSecond cue\n"))

(deftest parse-srt-dispatches-to-srt-codec
  (let [res (p.sub/parse codec srt-text :format/srt)]
    (is (r/ok? res) "valid SRT parses through the registry")
    (is (= 2 (count (:cues (:ok res)))) "both cue blocks are promoted")))

(deftest parse-unregistered-format-fails-loud
  (let [res (p.sub/parse codec srt-text :format/foo)]
    (is (r/err? res) "an unregistered format is rejected, not silently parsed")
    (is (= :error/unsupported-format (:error res)))
    (is (= :format/foo (:format res)) "the offending format is carried on the err")))

;; --- render: dispatch selects the codec by the track's :format --------------

(deftest render-srt-track-dispatches-to-srt-codec
  (let [res (r/let-ok [track (build-track :format/srt cue-specs)]
              (p.sub/render-bytes codec track))
        text (:ok res)]
    (is (r/ok? res) "SRT track renders through the registry")
    (is (str/includes? text "Hello world"))
    (is (str/includes? text "Second cue"))
    (is (str/includes? text "line two"))
    (is (str/includes? text "00:00:01,500") "SRT uses the ',' fractional separator")))

(deftest render-vtt-track-dispatches-to-vtt-codec
  (let [res (r/let-ok [track (build-track :format/vtt cue-specs)]
              (p.sub/render-bytes codec track))
        text (:ok res)]
    (is (r/ok? res) "VTT track renders through the registry")
    (is (str/starts-with? text "WEBVTT") "the WebVTT header marks the format")
    (is (str/includes? text "Hello world"))
    (is (str/includes? text "Second cue"))
    (is (str/includes? text "00:00:01.500") "WebVTT uses the '.' fractional separator")))

;; --- round-trip: render then parse the same format preserves the cue count --

(deftest render-then-parse-roundtrip-srt
  (let [n (count (:cues (:ok (r/let-ok [track (build-track :format/srt cue-specs)
                                        text  (p.sub/render-bytes codec track)]
                               (p.sub/parse codec text :format/srt)))))]
    (is (= (count cue-specs) n) "SRT round-trip preserves every cue")))

(deftest render-then-parse-roundtrip-vtt
  (let [n (count (:cues (:ok (r/let-ok [track (build-track :format/vtt cue-specs)
                                        text  (p.sub/render-bytes codec track)]
                               (p.sub/parse codec text :format/vtt)))))]
    (is (= (count cue-specs) n) "VTT round-trip preserves every cue")))
