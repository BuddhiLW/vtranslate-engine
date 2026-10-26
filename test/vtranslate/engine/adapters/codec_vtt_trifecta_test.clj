(ns vtranslate.engine.adapters.codec-vtt-trifecta-test
  (:require [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.adapters.codec.vtt :as c]
            [vtranslate.engine.port.subtitle :as sub]
            [vtranslate.engine.schema.port-values :as p]))
(defn observation [[start duration text :as in]]
  (let [codec (c/make-codec)
        rendered (sub/render-bytes codec {:cues [{:index 1 :range {:start {:ms start} :end {:ms (+ start duration)}} :lines [text]}]})]
    {:in in :out {:rendered rendered :parsed (sub/parse codec (:ok rendered) :format/vtt)}}))
(deftrifecta make-codec observation
  {:golden-path "test/golden/adapters-vtt-make.edn"
   :cases {:speech [0 1000 "hello"] :unicode [100 100 "世界"]}
   :gen (gen/tuple (gen/choose 0 10000000) (gen/choose 1 10000) (gen/fmap #(str "cue" %) gen/string-alphanumeric))
   :pred (fn [{[start duration text] :in {:keys [parsed rendered]} :out}]
           (and (m/validate p/RenderResult rendered) (m/validate p/ParseResult parsed)
                (= [{:index 1 :start-ms start :end-ms (+ start duration) :lines [text]}] (get-in parsed [:ok :cues]))))})
