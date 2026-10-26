(ns vtranslate.engine.adapters.timecode-trifecta-test
  (:require [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.adapters.codec.timecode :as t]))
(defn encode-observation [[ms sep :as in]] {:in in :out (t/ms->clock ms sep)})
(deftrifecta ms->clock encode-observation
  {:golden-path "test/golden/adapters-timecode-encode.edn" :cases {:zero [0 ","] :minute [60001 "."]}
   :gen (gen/tuple (gen/choose 0 359999999) (gen/elements ["," "."]))
   :pred (fn [{[ms sep] :in out :out}]
           (and (m/validate [:re #"\d{2}:\d{2}:\d{2}[,.]\d{3}"] out)
                (= ms (t/clock->ms out)) (= sep (subs out 8 9))))})
(defn decode-observation [[ms sep :as in]] {:in in :out (t/clock->ms (t/ms->clock ms sep))})
(deftrifecta clock->ms decode-observation
  {:golden-path "test/golden/adapters-timecode-decode.edn" :cases {:zero [0 ","] :hour [3600001 "."]}
   :gen (gen/tuple (gen/choose 0 359999999) (gen/elements ["," "."]))
   :pred (fn [{[ms] :in out :out}] (and (m/validate [:int {:min 0}] out) (= ms out)))})
