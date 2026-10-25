(ns vtranslate.engine.native.ffmpeg.burner-javacv-test
  "Native contract for burner.javacv/JavaCvBurner (IHardsubBurner) over the
   bundled bytedeco encoder. Run under :ffmpeg (see tests-native.edn).

   Relational property: burning keeps the clip (same frame timestamps, same
   duration within one frame, audio kept) and changes the picture on EXACTLY
   the frames a cue covers, as calc.overlay defines coverage."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.adapters.burner.javacv :as javacv]
            [vtranslate.engine.collect.ffmpeg :as ffmpeg]
            [vtranslate.engine.collect.protocols :as p]
            [vtranslate.engine.native.ffmpeg.generators :as g]
            [vtranslate.engine.native.media-fixture :as fx]
            [vtranslate.engine.port.burner :as p.burner]
            [vtranslate.engine.providers.burner-registry :as reg]
            [vtranslate.engine.schema.native-media :as nm]
            [vtranslate.engine.schema.port-values :as pv]))

(defn burn-observation
  "Burn `cues` into a fresh 1 s fixture with the real JavaCvBurner.
   => {:in cues :out {:result :probe :frames} :source-probe ...}; the temp dir
   is rewritten to <tmp> so the observation is stable."
  [cues]
  (fx/with-temp-dir [dir]
    (let [src    (fx/synth-source! dir)
          out    (str dir "/burned.mp4")
          track  (fx/rendered-track cues "en")
          result (p.burner/burn! (javacv/->JavaCvBurner) src out track {})
          media  (ffmpeg/make-backend)]
      {:in  cues
       :out {:result (str/replace result dir "<tmp>")
             :probe  (p/probe media out)
             :frames (fx/frame-observations src out track)}
       :source-probe (p/probe media src)})))

(defn- covered-frames
  "Frames on the 100 ms grid that `cues` cover (start inclusive, end exclusive)."
  [cues]
  (reduce + (map #(quot (- (:end-ms %) (:start-ms %)) fx/frame-ms) cues)))

(defn- holds?
  [{cues :in {:keys [result probe frames] :as out} :out source :source-probe}]
  (and (m/validate nm/CueSpecs cues)
       (m/validate nm/BurnObservation out)
       (m/validate pv/BurnValue result)
       (m/validate nm/MediaProbe source)
       (= "<tmp>/burned.mp4" result)
       (= (range 0 1000 fx/frame-ms) (map :t-ms frames))
       (<= (Math/abs (- (long (:duration-ms probe)) (long (:duration-ms source)))) fx/frame-ms)
       (:has-audio? probe)
       (:has-video? probe)
       (every? #(= (:cue-active? %) (:changed? %)) frames)
       (= (count (filter :cue-active? frames)) (covered-frames cues))))

(deftrifecta burn! burn-observation
  {:golden-path "test/golden/native-burner-javacv.edn"
   :cases {:one-cue  [{:start-ms 0 :end-ms 400 :lines ["hello"]}]
           :two-cues [{:start-ms 100 :end-ms 300 :lines ["first"]}
                      {:start-ms 500 :end-ms 900 :lines ["second" "two lines"]}]
           :adjacent [{:start-ms 200 :end-ms 500 :lines ["a"]}
                      {:start-ms 500 :end-ms 700 :lines ["b"]}]
           :unicode  [{:start-ms 300 :end-ms 1000 :lines ["Olá" "über ñ"]}]}
   :xf #(dissoc % :source-probe)
   :gen g/cues-gen
   :num-tests 12
   :pred holds?})

(deftest javacv-burner-is-registered-and-refuses-a-missing-source
  (is (some #{:javacv} (reg/known)))
  (is (instance? vtranslate.engine.adapters.burner.javacv.JavaCvBurner
                 (:ok (reg/resolve-burner :javacv {}))))
  (testing "a source that does not exist throws (the composer maps it to a Result)"
    (fx/with-temp-dir [dir]
      (is (thrown? Exception
                   (p.burner/burn! (javacv/->JavaCvBurner) (str dir "/missing.mp4")
                                   (str dir "/out.mp4")
                                   (fx/rendered-track [{:start-ms 0 :end-ms 100 :lines ["x"]}] "en")
                                   {}))))))
