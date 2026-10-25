(ns vtranslate.engine.calc.subtitle-in-trifecta-test
  (:require [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.schema :as schema]
            [vtranslate.engine.schema.calc-text :as s]
            [vtranslate.engine.calc.subtitle-in :as sut]
            [clojure.walk]))
(def spec {:id "track" :source-id "source" :language "en" :format :format/srt})
(def cue {:index 99 :start-ms 0 :end-ms 100 :lines ["hello"]})
(deftrifecta build-subtitle-track sut/build-subtitle-track
  {:golden-path "test/golden/calc-text-subtitle-in-build.edn" :apply? true
   :xf (fn [x] (clojure.walk/postwalk #(if (record? %) (into {} %) %) x))
   :cases {:renumber [[cue (assoc cue :index 0)] spec] :empty [[] spec]
           :blank [[(assoc cue :lines [])] spec] :bad-time [[(assoc cue :end-ms -1)] spec]
           :bad-format [[cue] (assoc spec :format :format/nope)]
           :bad-language [[cue] (assoc spec :language "xyz")]}
   :gen (gen/tuple
         (gen/vector (gen/fmap (fn [[start duration text]] (assoc cue :start-ms start :end-ms (+ start duration) :lines [text]))
                              (gen/tuple (gen/choose 0 10000) (gen/choose 1 1000) (gen/not-empty gen/string-alphanumeric))) 1 10)
         (gen/fmap #(assoc spec :format %) (gen/elements [:format/srt :format/vtt :format/ass])))
   :pred #(and (m/validate (schema/result-of s/Track) %) (:ok %)
               (= (range 1 (inc (count (:cues (:ok %))))) (map :index (:cues (:ok %)))))
   :mutations [["reject-valid" (constantly {:error :error/render-failed})]]})
