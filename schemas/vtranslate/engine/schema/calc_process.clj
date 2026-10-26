(ns vtranslate.engine.schema.calc-process
  "Value-only policy contracts. Does not read the environment or spawn threads."
  (:require [malli.core :as m]
            [vtranslate.engine.calc.native-stack :as stack]
            [vtranslate.engine.calc.subprocess-env :as env]))

(def Environment [:map-of :string :string])
(def Policy [:map
             [:allow {:optional true} [:set :string]]
             [:allow-prefixes {:optional true} [:sequential :string]]
             [:overrides {:optional true} [:maybe :map]]])
(def StackBytes [:int {:min 8388608}])
(def Withheld [:and [:vector :string] [:fn #(= % (vec (sort %))) ]])
(m/=> vtranslate.engine.calc.native-stack/stack-bytes [:=> [:cat [:maybe :map]] StackBytes])
(m/=> vtranslate.engine.calc.subprocess-env/allowed? [:=> [:cat Policy :any] :boolean])
(m/=> vtranslate.engine.calc.subprocess-env/sanitize [:function [:=> [:cat [:maybe :map]] Environment]
                    [:=> [:cat [:maybe :map] [:maybe Policy]] Environment]])
(m/=> vtranslate.engine.calc.subprocess-env/withheld [:function [:=> [:cat [:maybe :map]] Withheld]
                    [:=> [:cat [:maybe :map] [:maybe Policy]] Withheld]])
