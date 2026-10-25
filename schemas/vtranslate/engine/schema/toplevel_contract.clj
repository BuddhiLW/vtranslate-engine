(ns vtranslate.engine.schema.toplevel-contract)
(def Addon [:map [:ns [:maybe :symbol]] [:config :map]])
(def Aliases [:vector :keyword])
(def Bytes [:int {:min 0}])
(def Ledger [:map [:budget-bytes Bytes] [:resident [:map-of :string Bytes]]
             [:lru [:vector :string]] [:pinned [:set :string]]])
(def Refusal [:map [:error [:= :error/model-host]] [:model :string]
              [:needed-mib :int] [:short-mib :int] [:budget-mib :int]
              [:resident [:vector :string]] [:busy [:vector :string]]])
(def Settled [:map [:status [:= :ok]] [:value :any]])
(def Result [:or [:map [:ok :any]] [:map [:error :keyword]]])
(def Version [:string {:min 1}])
