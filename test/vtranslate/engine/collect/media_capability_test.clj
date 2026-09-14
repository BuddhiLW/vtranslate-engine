(ns vtranslate.engine.collect.media-capability-test
  "Capability facts survive the media boundary without guessing from filenames."
  (:require [clojure.test :refer [deftest is]]
            [hive-test.mutation :refer [deftest-mutations]]
            [hive-test.properties :refer [defprop-total]]
            [clojure.test.check.generators :as gen]
            [vtranslate.engine.collect.audio :as audio]))

(def facts {:container "mp4" :duration-ms 1000 :has-audio? false :audio-codec nil})

(deftest video-capability-is-explicit
  (doseq [present? [true false]]
    (let [projected (audio/to-probe-info (assoc facts :has-video? present?))]
      (is (= present? (:has-video? projected)))
      (is (false? (:has-audio? projected)))))
  (is (not (contains? (audio/to-probe-info facts) :has-video?))))

(defprop-total projection-is-total
  audio/to-probe-info
  (gen/fmap #(assoc facts :has-video? %) gen/boolean))

(deftest-mutations capability-survives-projection
  vtranslate.engine.collect.audio/to-probe-info
  [["drops-video" #(dissoc % :has-video?)]
   ["assumes-video" #(assoc % :has-video? true)]
   ["audio-implies-video" #(assoc % :has-video? (:has-audio? %))]]
  (fn []
    (doseq [present? [true false]]
      (is (= present? (:has-video? (audio/to-probe-info (assoc facts :has-video? present?))))))
    (is (not (contains? (audio/to-probe-info facts) :has-video?)))))
