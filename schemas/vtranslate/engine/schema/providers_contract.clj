(ns vtranslate.engine.schema.providers-contract)

(def Resolution [:map [:ok [:map [:key :keyword] [:config :map]]]])
(def Unknown [:map [:error :keyword]])
(def RegistryObservation
  [:map [:in :map] [:out [:vector [:map [:resolved Resolution] [:unknown Unknown]
                                   [:known? :boolean] [:default? :boolean]
                                   [:diagnostic? :boolean]]]]])
(def Routing [:map [:ok :map]])
(def Compatibility [:or Routing Unknown])
(def Decorated [:map [:ok [:vector :keyword]]])
(def Identities [:vector :string])
(def Contributions [:vector [:tuple :keyword [:map [:order :int]]]])
(def Keys [:vector :keyword])
