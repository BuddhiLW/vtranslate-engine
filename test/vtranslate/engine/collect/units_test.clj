(ns vtranslate.engine.collect.units-test
  "Trifecta — golden + property + mutation — for the µs→ms boundary conversion.
   This single calculation underwrites every timestamp in the pipeline, so it
   gets the full treatment: a snapshot of its rounding/clamp behaviour, a
   property over ALL longs, and a battery of broken impls the tests must catch."
  (:require [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [vtranslate.engine.collect.units]))

(deftrifecta us->ms
  vtranslate.engine.collect.units/us->ms
  {;; --- GOLDEN: lock rounding (round-half-up) + negative-clamp behaviour ---
   :golden-path "test/golden/units-us->ms.edn"
   :cases {:zero        0
           :exact-1ms   1000
           :round-down  1499
           :round-half  1500
           :two-minutes 121858000
           :neg-small   -1
           :av-nopts    -9223372036854775808}   ;; ffmpeg AV_NOPTS_VALUE (Long/MIN)

   ;; --- PROPERTY: total + always a non-negative integer for ANY long input ---
   :gen        gen/large-integer
   :pred       (fn [ms] (and (integer? ms) (<= 0 ms)))
   :num-tests  500

   ;; --- MUTATION: every broken impl must diverge on >=1 golden case ---
   :mutations [["no-neg-clamp" (fn [^long us] (Math/round (/ (double us) 1000)))]
               ["divisor-100"  (fn [^long us] (if (neg? us) 0 (Math/round (/ (double us) 100))))]
               ["floor-trunc"  (fn [^long us] (if (neg? us) 0 (long (/ us 1000))))]
               ["always-zero"  (fn [_] 0)]]})
