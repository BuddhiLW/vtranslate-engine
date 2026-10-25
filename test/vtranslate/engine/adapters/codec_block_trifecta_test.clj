(ns vtranslate.engine.adapters.codec-block-trifecta-test
  (:require [clojure.string :as str]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.adapters.codec.block :as b]
            [vtranslate.engine.adapters.codec.timecode :as tc]))
(defn cue-observation [[start duration text sep :as in]]
  {:in in :out (b/cue->block sep {:index 1 :range {:start {:ms start} :end {:ms (+ start duration)}} :lines [text]})})
(deftrifecta cue->block cue-observation
  {:golden-path "test/golden/adapters-block-cue.edn"
   :cases {:srt [0 1000 "hello" ","] :vtt [123 321 "世界" "."]}
   :gen (gen/tuple (gen/choose 0 10000000) (gen/choose 1 10000) gen/string-alphanumeric (gen/elements ["," "."]))
   :pred (fn [{[start duration text] :in out :out}]
           (let [[index times & lines] (str/split out #"\n" -1)
                 [a z] (str/split times #" --> ")]
             (and (m/validate :string out) (= "1" index) (= [text] lines)
                  (= [start (+ start duration)] (mapv tc/clock->ms [a z])))))})
(defn split-observation [blocks]
  {:in blocks :out (b/split-blocks (str "﻿" (str/join "\r\n\r\n" blocks) "\r\n"))})
(deftrifecta split-blocks split-observation
  {:golden-path "test/golden/adapters-block-split.edn"
   :cases {:one ["one"] :two ["one" "two"]}
   :gen (gen/vector (gen/fmap #(str "block" %) gen/string-alphanumeric) 1 12)
   :pred (fn [{:keys [in out]}] (and (m/validate [:vector :string] out) (= in out)))})
