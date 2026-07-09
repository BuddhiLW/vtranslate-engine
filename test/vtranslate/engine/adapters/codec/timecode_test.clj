(ns vtranslate.engine.adapters.codec.timecode-test
  "Trifecta — golden + property + mutation — for the wire<->ms timecode boundary.
   Both fns are pure and scalar (EDN-safe), so nothing to project."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [hive-test.golden :refer [deftest-golden]]
            [hive-test.mutation :refer [deftest-mutations]]
            [vtranslate.engine.adapters.codec.timecode :as sut]))

;; =============================================================================
;; GOLDEN — representative ms<->clock table for both separators (SRT ",", VTT ".")
;; =============================================================================

(deftest-golden timecode-golden
  "test/golden/timecode-table.edn"
  {:ms->clock-srt {:zero       (sut/ms->clock 0 ",")
                   :sub-second (sut/ms->clock 1 ",")
                   :half-sec   (sut/ms->clock 1500 ",")
                   :one-min    (sut/ms->clock 61000 ",")
                   :hours      (sut/ms->clock 3661001 ",")
                   :distinct   (sut/ms->clock 3723456 ",")
                   :ten-hours  (sut/ms->clock 36000000 ",")}
   :ms->clock-vtt {:zero       (sut/ms->clock 0 ".")
                   :sub-second (sut/ms->clock 1 ".")
                   :half-sec   (sut/ms->clock 1500 ".")
                   :hours      (sut/ms->clock 3661001 ".")
                   :distinct   (sut/ms->clock 3723456 ".")}
   :clock->ms {:zero        (sut/clock->ms "00:00:00,000")
               :sub-second  (sut/clock->ms "00:00:00,001")
               :half-sec    (sut/clock->ms "00:00:01.500")
               :hours       (sut/clock->ms "01:01:01,001")
               :distinct    (sut/clock->ms "01:02:03,456")
               :ten-hours   (sut/clock->ms "10:00:00,000")
               :no-hour     (sut/clock->ms "02:03,456")
               :trimmed     (sut/clock->ms "  00:00:00,000  ")
               :short-ms    (sut/clock->ms "00:00:01,5")
               :garbage     (sut/clock->ms "not-a-timecode")
               :empty       (sut/clock->ms "")}})

;; =============================================================================
;; PROPERTY — roundtrip (both separators), totality, nil-on-garbage
;; =============================================================================

;; ms->clock pads hours to %02d and clock-re accepts at most 2 hour digits,
;; so roundtrip holds exactly for 0..99 hours (0 .. 359999999 ms).
(def ^:private gen-roundtrip-ms (gen/choose 0 359999999))

(defspec roundtrip-srt 300
  (prop/for-all [ms gen-roundtrip-ms]
    (= ms (sut/clock->ms (sut/ms->clock ms ",")))))

(defspec roundtrip-vtt 300
  (prop/for-all [ms gen-roundtrip-ms]
    (= ms (sut/clock->ms (sut/ms->clock ms ".")))))

(defspec ms->clock-total-string 200
  (prop/for-all [ms gen/large-integer
                 sep (gen/elements ["," "."])]
    (string? (sut/ms->clock ms sep))))

;; alphanumeric strings contain no ':' or [,.] so can never match clock-re
(defspec clock->ms-nil-on-garbage 200
  (prop/for-all [s gen/string-alphanumeric]
    (nil? (sut/clock->ms s))))

;; =============================================================================
;; MUTATION — broken conversions must diverge on the roundtrip/golden cases
;; =============================================================================

(deftest-mutations ms->clock-mutations-caught
  vtranslate.engine.adapters.codec.timecode/ms->clock
  [["drop-hours"    (fn [ms sep] (format "%02d:%02d:%02d%s%03d"
                                         0
                                         (quot (rem ms 3600000) 60000)
                                         (quot (rem ms 60000) 1000)
                                         sep (rem ms 1000)))]
   ["hour-divisor"  (fn [ms sep] (format "%02d:%02d:%02d%s%03d"
                                         (quot ms 60000)
                                         (quot (rem ms 3600000) 60000)
                                         (quot (rem ms 60000) 1000)
                                         sep (rem ms 1000)))]
   ["swap-min-sec"  (fn [ms sep] (format "%02d:%02d:%02d%s%03d"
                                         (quot ms 3600000)
                                         (quot (rem ms 60000) 1000)
                                         (quot (rem ms 3600000) 60000)
                                         sep (rem ms 1000)))]
   ["ms-divisor"    (fn [ms sep] (format "%02d:%02d:%02d%s%03d"
                                         (quot ms 3600000)
                                         (quot (rem ms 3600000) 60000)
                                         (quot (rem ms 60000) 1000)
                                         sep (rem ms 100)))]]
  (fn []
    (is (= "00:00:00,000" (sut/ms->clock 0 ",")))
    (is (= "00:00:00,001" (sut/ms->clock 1 ",")))
    (is (= "00:00:01.500" (sut/ms->clock 1500 ".")))
    (is (= "01:01:01,001" (sut/ms->clock 3661001 ",")))
    (is (= "01:02:03,456" (sut/ms->clock 3723456 ",")))
    (is (= "10:00:00,000" (sut/ms->clock 36000000 ",")))))

(deftest-mutations clock->ms-mutations-caught
  vtranslate.engine.adapters.codec.timecode/clock->ms
  [["no-trim"      (fn [s] (when-let [[_ h m sec ms]
                                      (re-matches #"(?:(\d{1,2}):)?(\d{1,2}):(\d{2})[,.](\d{1,3})" s)]
                             (+ (* (if h (parse-long h) 0) 3600000)
                                (* (parse-long m) 60000)
                                (* (parse-long sec) 1000)
                                (parse-long ms))))]
   ["min-divisor"  (fn [s] (when-let [[_ h m sec ms]
                                      (re-matches #"(?:(\d{1,2}):)?(\d{1,2}):(\d{2})[,.](\d{1,3})" (str/trim s))]
                             (+ (* (if h (parse-long h) 0) 3600000)
                                (* (parse-long m) 1000)
                                (* (parse-long sec) 1000)
                                (parse-long ms))))]
   ["drop-hours"   (fn [s] (when-let [[_ _h m sec ms]
                                      (re-matches #"(?:(\d{1,2}):)?(\d{1,2}):(\d{2})[,.](\d{1,3})" (str/trim s))]
                             (+ (* (parse-long m) 60000)
                                (* (parse-long sec) 1000)
                                (parse-long ms))))]
   ["never-nil"    (fn [_] 0)]]
  (fn []
    (is (= 0 (sut/clock->ms "00:00:00,000")))
    (is (= 1500 (sut/clock->ms "00:00:01.500")))
    (is (= 3661001 (sut/clock->ms "01:01:01,001")))
    (is (= 3723456 (sut/clock->ms "01:02:03,456")))
    (is (= 36000000 (sut/clock->ms "10:00:00,000")))
    (is (= 0 (sut/clock->ms "  00:00:00,000  ")))
    (is (nil? (sut/clock->ms "not-a-timecode")))))
