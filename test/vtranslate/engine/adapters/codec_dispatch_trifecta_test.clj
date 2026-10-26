(ns vtranslate.engine.adapters.codec-dispatch-trifecta-test
  (:require [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.adapters.codec.dispatch :as c]
            [vtranslate.engine.port.subtitle :as sub]
            [vtranslate.engine.schema.port-values :as p]))
(defn observation [[format text :as in]]
  (let [codec (c/make-codec)
        rendered (sub/render-bytes codec {:format format :cues [{:index 1 :range {:start {:ms 0} :end {:ms 1000}} :lines [text]}]})]
    {:in in :out {:rendered rendered :parsed (sub/parse codec (:ok rendered) format)}}))
(deftrifecta make-codec observation
  {:golden-path "test/golden/adapters-dispatch-make.edn"
   :cases {:srt [:format/srt "hello"] :vtt [:format/vtt "世界"]}
   :gen (gen/tuple (gen/elements [:format/srt :format/vtt]) (gen/fmap #(str "cue" %) gen/string-alphanumeric))
   :pred (fn [{[_ text] :in {:keys [parsed rendered]} :out}]
           (and (m/validate p/RenderResult rendered) (m/validate p/ParseResult parsed)
                (= [{:index 1 :start-ms 0 :end-ms 1000 :lines [text]}] (get-in parsed [:ok :cues]))))})
