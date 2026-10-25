(ns vtranslate.engine.adapters.composer-both-trifecta-test
  (:require [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.adapters.composer.both :as b]
            [vtranslate.engine.port.composer :as port]
            [vtranslate.engine.schema.port-values :as p]))
(defn observation [base]
  (let [calls (atom [])
        delegate (reify port/IVideoComposer
                   (compose [_ _ _ opts] (swap! calls conj (:output-uri opts)) {:ok {:output-uri (:output-uri opts)}}))
        result (port/compose (b/make-composer delegate delegate) "fixture.mp4" {} {:output-uri base})]
    {:in base :out {:result result :calls @calls}}))
(deftrifecta make-composer observation
  {:golden-path "test/golden/adapters-composer-both-make.edn"
   :cases {:plain "out.mp4" :spaces "out file.mp4"}
   :gen (gen/fmap #(str "out" % ".mp4") gen/string-alphanumeric)
   :pred (fn [{base :in {:keys [result calls] :as out} :out}]
           (and (m/validate [:map [:result p/ComposeResult] [:calls [:vector :string]]] out)
                (= calls (mapv #(get-in result [:ok :output-uris %]) [:soft :hard]))
                (= 2 (count (set calls))) (every? #(.startsWith ^String % (subs base 0 (- (count base) 4))) calls)))})
