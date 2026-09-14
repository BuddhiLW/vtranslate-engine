(ns vtranslate.engine.calc.coverage-test
  "The coverage contract is what stops a segmenter from silently deciding that
   half a film has no dialogue in it: whatever it leaves out is tiled, so every
   second of audio reaches ASR and whisper — not the VAD — decides what is
   speech."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [vtranslate.engine.calc.coverage :as cov]))

(defn- covered-ms
  "Total ms covered by `spans`, counting overlapped audio once."
  [spans]
  (->> spans
       (sort-by :start-ms)
       (reduce (fn [[total reach] {:keys [start-ms end-ms]}]
                 [(+ total (max 0 (- end-ms (max start-ms reach))))
                  (max reach end-ms)])
               [0 0])
       first))

(defn- ordered-non-overlapping? [spans]
  (every? (fn [[a b]] (<= (:end-ms a) (:start-ms b))) (partition 2 1 spans)))

;; Measured 2026-09-14 on corpus/longform/sintel_tail180s.mp4: silero accepted
;; ONE span of 1.7 s in 180 s of audio carrying five official cues, and the job
;; rendered one cue. Anything that leaves that shape intact is not a fix.
(deftest a-vad-that-accepts-almost-nothing-still-covers-the-clip
  (let [spans [{:start-ms 440 :end-ms 2152}]
        out   (cov/fill-gaps spans 180000 15000 1000)]
    (testing "the VAD's own span survives exactly — it carries the utterance bounds"
      (is (some #{{:start-ms 440 :end-ms 2152}} out)))
    (testing "and the other 178 seconds are tiled rather than dropped"
      (is (empty? (cov/gaps out 180000 1000))
          "no stretch of a second or more is left uncovered")
      (is (<= 179000 (covered-ms out) 180000)
          "only sub-second holes remain, which the decode pad already covers")
      (is (ordered-non-overlapping? out)))))

(deftest gaps-shorter-than-the-floor-are-left-to-the-decode-pad
  (let [spans [{:start-ms 0 :end-ms 5000} {:start-ms 5500 :end-ms 10000}]
        out   (cov/fill-gaps spans 10000 15000 1000)]
    (is (= spans out) "a 500 ms gap is not worth a window of its own")))

(deftest an-empty-segmentation-becomes-a-plain-grid
  (let [out (cov/fill-gaps [] 35000 15000 1000)]
    (is (= [{:start-ms 0 :end-ms 15000}
            {:start-ms 15000 :end-ms 30000}
            {:start-ms 30000 :end-ms 35000}]
           out))))

(deftest spans-are-clamped-to-the-media
  (testing "a span past the end of the clip cannot pull the tiling past it either"
    (let [out (cov/fill-gaps [{:start-ms 9000 :end-ms 99000}] 10000 15000 1000)]
      (is (= [{:start-ms 0 :end-ms 9000} {:start-ms 9000 :end-ms 10000}] out))
      (is (= 10000 (covered-ms out))))))

(deftest unknown-duration-changes-nothing
  (testing "no probed duration => the inner spans pass through untouched"
    (is (= [{:start-ms 10 :end-ms 20}]
           (cov/fill-gaps [{:start-ms 10 :end-ms 20}] 0 15000 1000)))))

(deftest gaps-reports-what-is-uncovered
  (is (= [{:start-ms 0 :end-ms 1000} {:start-ms 3000 :end-ms 8000}]
         (cov/gaps [{:start-ms 1000 :end-ms 3000}] 8000 500))))

(def gen-span
  (gen/let [a (gen/choose 0 60000)
            len (gen/choose 1 5000)]
    {:start-ms a :end-ms (+ a len)}))

(defspec filled-spans-cover-the-whole-clip 200
  (prop/for-all [spans    (gen/vector gen-span 0 20)
                 duration (gen/choose 1 60000)]
    (let [out (cov/fill-gaps spans duration 15000 0)]
      (and (ordered-non-overlapping? out)
           (every? #(and (<= 0 (:start-ms %)) (<= (:end-ms %) duration)) out)
           (= duration (covered-ms out))))))

(defspec every-inner-span-survives-verbatim 200
  (prop/for-all [spans    (gen/vector gen-span 0 20)
                 duration (gen/choose 1 60000)]
    (let [in-bounds (filter #(<= (:end-ms %) duration) spans)
          out       (set (cov/fill-gaps spans duration 15000 1000))]
      ;; every span the segmenter reported, when it does not overlap another,
      ;; comes back unchanged: the decorator adds, it never re-cuts
      (every? (fn [s]
                (or (some (fn [o] (and (not= o s)
                                       (< (:start-ms o) (:end-ms s))
                                       (< (:start-ms s) (:end-ms o))))
                          out)
                    (contains? out s)))
              in-bounds))))
