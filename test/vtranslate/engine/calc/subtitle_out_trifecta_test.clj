(ns vtranslate.engine.calc.subtitle-out-trifecta-test
  (:require [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.schema :as schema]
            [vtranslate.engine.schema.calc-text :as s]
            [vtranslate.engine.calc.subtitle-out :as sut]))
(def cue-gen (gen/hash-map :index gen/nat :start-ms gen/nat :end-ms gen/nat
                           :lines (gen/vector gen/string-alphanumeric 0 5)))
(deftrifecta cue-texts sut/cue-texts
  {:golden-path "test/golden/calc-text-subtitle-out-texts.edn" :apply? true
   :cases {:empty [[]] :multiline [[{:lines ["one" "two"]} {:lines []}]]}
   :gen (gen/tuple (gen/vector cue-gen 0 10))
   :pred #(m/validate s/Strings %)
   :mutations [["drop-texts" (constantly [])]]})
(deftrifecta apply-translations sut/apply-translations
  {:golden-path "test/golden/calc-text-subtitle-out-apply.edn" :apply? true
   :cases {:empty [[] []] :mismatch [[{:lines ["a"]}] []]
           :multiline [[{:index 7 :start-ms 0 :end-ms 10 :lines ["a"]}] ["b\r\nc"]]}
   :gen (gen/bind (gen/vector cue-gen 0 10)
                  (fn [cues] (gen/tuple (gen/return cues)
                                        (gen/vector (gen/fmap #(str % "\nnext") gen/string-alphanumeric) (count cues)))))
   :pred #(and (m/validate (schema/result-of s/Cues) %) (:ok %))
   :mutations [["count-always-wrong" (constantly {:error :error/translation-failed})]]})
