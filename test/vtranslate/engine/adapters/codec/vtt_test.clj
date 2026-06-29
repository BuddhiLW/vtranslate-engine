(ns vtranslate.engine.adapters.codec.vtt-test
  "WebVTT codec — golden wire format, render⇄parse round-trip, the renderer LSP
   contract, and tolerance of NOTE blocks / cue settings / missing identifiers."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [hive-dsl.result :as r]
            [vtranslate.engine.adapters.codec.vtt :as vtt]
            [vtranslate.engine.contract.ports-contract :as contract]
            [vtranslate.engine.domain.rendering :as rd]
            [vtranslate.engine.port.subtitle :as p.sub]))

(def codec (vtt/make-codec))

(defn- track-of
  [cues]
  (let [t0 (:ok (rd/make-subtitle-track
                 {:id "s" :source-id "c" :language "pt-BR" :format :format/vtt}))]
    (:ok (rd/render (reduce (fn [t c] (rd/add-cue t (:ok (rd/make-cue c)))) t0 cues)))))

(defn- cue-data [c] (select-keys c [:index :start-ms :end-ms :lines]))

;; --- GOLDEN: header + '.' separator + multi-line ----------------------------

(deftest render-golden
  (let [track (track-of [{:index 1 :start-ms 0    :end-ms 1500 :lines ["Hello"]}
                         {:index 2 :start-ms 1500 :end-ms 3200 :lines ["two" "lines"]}])]
    (is (= (str "WEBVTT\n\n"
                "1\n00:00:00.000 --> 00:00:01.500\nHello\n\n"
                "2\n00:00:01.500 --> 00:00:03.200\ntwo\nlines\n")
           (:ok (p.sub/render-bytes codec track))))))

;; --- ROUND-TRIP -------------------------------------------------------------

(def ^:private gen-line (gen/such-that (complement str/blank?) gen/string-alphanumeric))

(def ^:private gen-cue
  (gen/fmap (fn [[idx start dur lines]]
              {:index (inc idx) :start-ms start :end-ms (+ start dur) :lines lines})
            (gen/tuple gen/nat (gen/choose 0 35000000) (gen/choose 0 10000)
                       (gen/vector gen-line 1 3))))

(defspec render-parse-roundtrip 250
  (prop/for-all [cues (gen/vector gen-cue 1 8)]
    (let [text   (:ok (p.sub/render-bytes codec (track-of cues)))
          parsed (:cues (:ok (p.sub/parse codec text :format/vtt)))]
      (= (map cue-data cues) (map cue-data parsed)))))

;; --- LSP contract + format guard --------------------------------------------

(deftest renderer-contract-vtt
  (contract/check-renderer codec (track-of [{:index 1 :start-ms 0 :end-ms 1000 :lines ["oi"]}])))

(deftest parse-rejects-foreign-format
  (is (r/err? (p.sub/parse codec "WEBVTT\n\n00:00:00.000 --> 00:00:01.000\nx\n" :format/srt))))

;; --- WebVTT tolerance: NOTE block, cue settings, missing identifier ---------

(deftest parse-skips-metadata-and-settings
  (let [doc  (str "WEBVTT\n\n"
                  "NOTE this is a comment\n\n"
                  "00:00:01.000 --> 00:00:04.000 align:start position:50%\n"
                  "Hello world\n")
        cues (:cues (:ok (p.sub/parse codec doc :format/vtt)))]
    (is (= 1 (count cues)) "NOTE block dropped, one cue parsed")
    (is (= {:index 0 :start-ms 1000 :end-ms 4000 :lines ["Hello world"]}
           (first cues))
        "missing identifier => index 0; trailing cue settings ignored")))
