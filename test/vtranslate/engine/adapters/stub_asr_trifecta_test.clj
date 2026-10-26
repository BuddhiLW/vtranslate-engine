(ns vtranslate.engine.adapters.stub-asr-trifecta-test
  (:require [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.adapters.transcriber.stub :as s]
            [vtranslate.engine.port.transcriber :as port]
            [vtranslate.engine.schema.port-values :as p]))
(def input-gen (gen/tuple (gen/choose -1 2000) (gen/choose 1 500) (gen/elements [nil "en" "he"])))
(defn segments-observation [[duration window language :as in]] {:in in :out (s/stub-segments duration window language)})
(defn tiling? [{[duration window language] :in out :out}]
  (and (m/validate p/Segments out) (= (max 1 duration) (:end-ms (peek out)))
       (= 0 (:start-ms (first out)))
       (= (max 1 duration) (reduce + (map #(- (:end-ms %) (:start-ms %)) out)))
       (every? #(<= (- (:end-ms %) (:start-ms %)) (max 1 window)) out)
       (= (mapv #(format "[%s segment %d]" (or language "und") %) (range 1 (inc (count out)))) (mapv :text out))))
(deftrifecta stub-segments segments-observation
  {:golden-path "test/golden/adapters-stub-asr-segments.edn" :cases {:minimum [0 10 nil] :tail [21 10 "he"]}
   :gen input-gen :pred tiling?})
(defn make-observation [[duration window language :as in]]
  {:in in :out (port/transcribe (s/make-transcriber window) {:duration-ms duration} language {})})
(deftrifecta make-transcriber make-observation
  {:golden-path "test/golden/adapters-stub-asr-make.edn" :cases {:minimum [0 10 nil] :tail [21 10 "he"]}
   :gen input-gen :pred (fn [{:keys [in out]}] (and (m/validate p/TranscribeResult out) (tiling? {:in in :out (get-in out [:ok :segments])})))})
