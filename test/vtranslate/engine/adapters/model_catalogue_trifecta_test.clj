(ns vtranslate.engine.adapters.model-catalogue-trifecta-test
  (:require [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.adapters.support.model-catalogue :as c]))
(def pair-gen (gen/tuple (gen/elements [:venice :openrouter :unknown]) (gen/elements [:translate :review :audio :story :comprehend :unknown])))
(defn model-observation [[provider role :as in]] {:in in :out (c/model-for provider role)})
(deftrifecta model-for model-observation
  {:golden-path "test/golden/adapters-catalogue-model.edn"
   :cases {:known [:venice :translate] :unknown [:unknown :translate] :role [:venice :unknown]}
   :gen pair-gen
   :pred (fn [{[provider role] :in out :out}]
           (and (m/validate [:maybe :string] out) (= out (get-in c/models [provider role]))))})
(def Defaults [:map [:api-url :string] [:secret-env :string] [:model :string]])
(defn defaults-observation [[provider role :as in]] {:in in :out (c/defaults-for provider role)})
(deftrifecta defaults-for defaults-observation
  {:golden-path "test/golden/adapters-catalogue-defaults.edn"
   :cases {:known [:openrouter :audio] :unknown [:unknown :translate]}
   :gen pair-gen
   :pred (fn [{[provider role] :in out :out}]
           (and (m/validate [:maybe Defaults] out)
                (= (:model out) (c/model-for provider role))
                (if out (= (dissoc out :model) (get c/providers provider)) true)))})
(defn secret-observation [[provider path :as in]]
  {:in in :out (c/secret-pass-for {:secrets {provider {:pass path}}} provider)})
(deftrifecta secret-pass-for secret-observation
  {:golden-path "test/golden/adapters-catalogue-secret.edn"
   :cases {:absent [:venice nil] :configured [:openrouter "vault/key"]}
   :gen (gen/tuple gen/keyword (gen/one-of [(gen/return nil) gen/string-alphanumeric]))
   :pred (fn [{[_ path] :in out :out}] (and (m/validate [:maybe :string] out) (= path out)))})
(defn providers-observation [role] {:in role :out (c/provider-defaults role #(keyword "role" (name %)))})
(deftrifecta provider-defaults providers-observation
  {:golden-path "test/golden/adapters-catalogue-providers.edn"
   :cases {:translate :translate :unknown :unknown}
   :gen (gen/elements [:translate :review :audio :story :comprehend :unknown])
   :pred (fn [{role :in out :out}]
           (and (m/validate [:map-of :keyword Defaults] out)
                (= out (into {} (map (fn [[k v]] [(keyword "role" (name k)) v]) (c/provider-defaults role))))
                (every? (fn [[k v]] (= (:model v) (c/model-for (keyword (name k)) role))) out)))})
