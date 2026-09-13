(ns vtranslate.engine.calc.progress-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [vtranslate.engine.calc.progress :as progress]))

(deftest a-stage-runs-from-its-floor-to-the-next-ones
  (is (= [60 85] ((juxt progress/floor-of progress/ceiling-of) :translating)))
  (is (= [95 100] ((juxt progress/floor-of progress/ceiling-of) :composing)))
  (is (nil? (progress/floor-of :warp-drive))))

(deftest counted-work-moves-through-the-band
  (is (= [68 76 84] (map #(progress/within-band :translating % 3) [1 2 3]))
      "three languages, and the last one finished is still not rendering")
  (is (= [96 98 99] (map #(progress/within-band :composing % 3) [1 2 3]))))

(deftest nothing-counted-is-the-floor
  (is (= 60 (progress/within-band :translating 0 3)))
  (is (= 60 (progress/within-band :translating 5 0)) "no work to count says nothing new"))

(defspec a-count-never-leaves-its-band 300
  (prop/for-all [[stage] (gen/elements (butlast progress/stage-floors))
                 total   (gen/choose 0 50)
                 done    (gen/choose -5 60)]
    (let [p (progress/within-band stage done total)]
      (<= (progress/floor-of stage) p (dec (progress/ceiling-of stage))))))

(defspec more-work-done-never-reads-as-less 300
  (prop/for-all [[stage] (gen/elements (butlast progress/stage-floors))
                 total   (gen/choose 1 50)
                 a       (gen/choose 0 50)
                 b       (gen/choose 0 50)]
    (<= (progress/within-band stage (min a b) total)
        (progress/within-band stage (max a b) total))))
