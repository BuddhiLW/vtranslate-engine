(ns vtranslate.engine.adapters.codec.srt-test
  "SRT codec — golden wire format, render⇄parse round-trip property, the
   renderer LSP contract (reused from ports-contract), and a smoke parse of a
   real multilingual corpus subtitle."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [hive-dsl.result :as r]
            [vtranslate.engine.adapters.codec.srt :as srt]
            [vtranslate.engine.contract.ports-contract :as contract]
            [vtranslate.engine.domain.rendering :as rd]
            [vtranslate.engine.port.subtitle :as p.sub]))

(def codec (srt/make-codec))

(defn- track-of
  "Build a rendered SubtitleTrack from [{:index :start-ms :end-ms :lines}...]."
  [cues]
  (let [t0 (:ok (rd/make-subtitle-track
                 {:id "s" :source-id "c" :language "pt-BR" :format :format/srt}))]
    (:ok (rd/render (reduce (fn [t c] (rd/add-cue t (:ok (rd/make-cue c)))) t0 cues)))))

(defn- cue-data [c] (select-keys c [:index :start-ms :end-ms :lines]))

;; --- GOLDEN: lock the exact wire format (timestamps, separators, multi-line) -

(deftest render-golden
  (let [track (track-of [{:index 1 :start-ms 0    :end-ms 1500 :lines ["Hello"]}
                         {:index 2 :start-ms 1500 :end-ms 3200 :lines ["two" "lines"]}])]
    (is (= (str "1\n00:00:00,000 --> 00:00:01,500\nHello\n\n"
                "2\n00:00:01,500 --> 00:00:03,200\ntwo\nlines\n")
           (:ok (p.sub/render-bytes codec track))))))

;; --- ROUND-TRIP: parse ∘ render preserves every cue's content ---------------

(def ^:private gen-line (gen/such-that (complement str/blank?) gen/string-alphanumeric))

(def ^:private gen-cue
  (gen/fmap (fn [[idx start dur lines]]
              {:index (inc idx) :start-ms start :end-ms (+ start dur) :lines lines})
            (gen/tuple gen/nat (gen/choose 0 35000000) (gen/choose 0 10000)
                       (gen/vector gen-line 1 3))))

(defspec render-parse-roundtrip 250
  (prop/for-all [cues (gen/vector gen-cue 1 8)]
    (let [text   (:ok (p.sub/render-bytes codec (track-of cues)))
          parsed (:cues (:ok (p.sub/parse codec text :format/srt)))]
      (= (map cue-data cues) (map cue-data parsed)))))

;; --- LSP: the real codec satisfies the renderer contract --------------------

(deftest renderer-contract-srt
  (contract/check-renderer codec (track-of [{:index 1 :start-ms 0 :end-ms 1000 :lines ["oi"]}])))

;; --- format guard -----------------------------------------------------------

(deftest parse-rejects-foreign-format
  (is (r/err? (p.sub/parse codec "1\n00:00:00,000 --> 00:00:01,000\nx\n" :format/vtt))))

;; --- SMOKE: parse a real CC-BY multilingual subtitle from the corpus --------

(deftest corpus-parse-smoke
  (let [f (io/file "../corpus/sintel/subs/sintel.en.srt")]
    (if-not (.exists f)
      (is true "corpus fixture absent — smoke test skipped")
      (let [res  (p.sub/parse codec (slurp f) :format/srt)
            cues (:cues (:ok res))]
        (is (r/ok? res))
        (is (pos? (count cues)) "parsed at least one cue")
        (is (every? #(<= (:start-ms %) (:end-ms %)) cues) "every range is ordered")
        (is (every? #(seq (:lines %)) cues) "every cue carries text lines")))))
