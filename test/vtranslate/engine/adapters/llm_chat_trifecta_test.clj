(ns vtranslate.engine.adapters.llm-chat-trifecta-test
  (:require [cheshire.core :as json]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.adapters.support.llm-chat :as chat]))
(def Body [:map [:model :string] [:temperature number?]
           [:messages [:vector [:map [:role :string] [:content :string]]]]])
(defn messages-observation [[model messages temperature :as in]]
  {:in in :out (json/parse-string (chat/chat-body-messages model messages {:temperature temperature}) true)})
(deftrifecta chat-body-messages messages-observation
  {:golden-path "test/golden/adapters-chat-messages.edn"
   :cases {:empty ["model" [] 0.2] :order ["m" [{:role "system" :content "static"} {:role "user" :content "שלום"}] 0]}
   :gen (gen/tuple gen/string-alphanumeric
                   (gen/vector (gen/hash-map :role (gen/elements ["system" "user" "assistant"])
                                             :content gen/string-alphanumeric) 0 8)
                   (gen/elements [0 0.2 1]))
   :pred (fn [{[model messages temperature] :in out :out}]
           (and (m/validate Body out) (= model (:model out)) (= messages (:messages out))
                (= temperature (:temperature out))))})
(defn body-observation [[model system user :as in]]
  {:in in :out (json/parse-string (chat/chat-body model system user {}) true)})
(deftrifecta chat-body body-observation
  {:golden-path "test/golden/adapters-chat-body.edn"
   :cases {:empty ["m" "" ""] :unicode ["m" "Translate" "世界"]}
   :gen (gen/tuple gen/string-alphanumeric gen/string-alphanumeric gen/string-alphanumeric)
   :pred (fn [{[model system user] :in out :out}]
           (and (m/validate Body out) (= model (:model out)) (= 0.2 (:temperature out))
                (= [{:role "system" :content system} {:role "user" :content user}] (:messages out))))})
(defn fences-observation [[text fenced? :as in]]
  {:in in :out (chat/strip-fences (if fenced? (str "```json\n" text "\n```") text))})
(deftrifecta strip-fences fences-observation
  {:golden-path "test/golden/adapters-chat-fences.edn"
   :cases {:bare ["[]" false] :fenced ["[\"שלום\"]" true] :empty ["" true]}
   :gen (gen/tuple (gen/fmap #(str "[\"" % "\"]") gen/string-alphanumeric) gen/boolean)
   :pred (fn [{[text] :in out :out}]
           (and (m/validate :string out) (= text out) (= out (chat/strip-fences out))))})
