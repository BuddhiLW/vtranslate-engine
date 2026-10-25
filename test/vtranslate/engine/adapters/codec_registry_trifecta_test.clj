(ns vtranslate.engine.adapters.codec-registry-trifecta-test
  (:require [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.adapters.codec.registry :as r]))
(defn register-observation [[registry k value :as in]] {:in in :out (r/register registry k value)})
(deftrifecta register register-observation
  {:golden-path "test/golden/adapters-codec-registry-register.edn"
   :cases {:new [{} :srt "codec"] :replace [{:srt "old" :vtt "other"} :srt "new"]}
   :gen (gen/tuple (gen/map gen/keyword gen/string-alphanumeric {:max-elements 8}) gen/keyword gen/string-alphanumeric)
   :pred (fn [{[registry k value] :in out :out}]
           (and (m/validate [:map-of :keyword :string] out) (= value (get out k))
                (= (dissoc registry k) (dissoc out k))))})
(defn lookup-observation [[registry k :as in]] {:in in :out (r/codec-for registry k)})
(deftrifecta codec-for lookup-observation
  {:golden-path "test/golden/adapters-codec-registry-lookup.edn"
   :cases {:found [{:srt "codec"} :srt] :absent [{} :srt]}
   :gen (gen/tuple (gen/map gen/keyword gen/string-alphanumeric {:max-elements 8}) gen/keyword)
   :pred (fn [{[registry k] :in out :out}]
           (and (m/validate [:or [:map [:ok :string]] [:map [:error :keyword] [:format :keyword]]] out)
                (= out (if-let [value (get registry k)] {:ok value} {:error :error/unsupported-format :format k}))))})
