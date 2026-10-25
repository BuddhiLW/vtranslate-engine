(ns vtranslate.engine.adapters.llm-translator-trifecta-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [cheshire.core :as json]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.adapters.translator.llm :as l]
            [vtranslate.engine.adapters.support.llm-chat :as chat]
            [vtranslate.engine.schema.port-values :as p]
            [vtranslate.engine.port.translator :as tr]))
(defn messages-observation [texts]
  {:in texts :out (l/default-messages {:texts texts :source-language "en" :target-language "he" :opts {}})})
(deftrifecta default-messages messages-observation
  {:golden-path "test/golden/adapters-llm-messages.edn"
   :cases {:empty [] :duplicate ["hello" "hello"] :unicode ["שלום"]}
   :gen (gen/vector gen/string-alphanumeric 0 12)
   :pred (fn [{:keys [in out]}]
           (and (m/validate [:vector [:map [:role :string] [:content :string]]] out)
                (= in (vec (json/parse-string (:content (second out)))))) )})
(defn make-observation [[provider model path :as in]]
  {:in in :out (into {} (l/make-translator provider {:translator-opts {:model model :secret-pass path}}))})
(deftrifecta make-translator make-observation
  {:golden-path "test/golden/adapters-llm-config.edn"
   :cases {:venice [:venice "override" nil] :router [:openrouter "other" "vault/key"]}
   :gen (gen/tuple (gen/elements [:venice :openrouter]) gen/string-alphanumeric
                   (gen/one-of [(gen/return nil) gen/string-alphanumeric]))
   :pred (fn [{[provider model path] :in out :out}]
           (and (m/validate [:map [:provider :keyword] [:api-url :string] [:model :string]
                            [:secret-env :string] [:secret-pass [:maybe :string]]] out)
                (= [provider model path] ((juxt :provider :model :secret-pass) out))))})
;; Private reply parser is explicitly exercised as a pure helper too.
(defn parse-observation [[texts wrapped? :as in]]
  {:in in :out (#'l/parse-translations (json/generate-string (if wrapped? {:translations texts} texts)) (count texts))})
(deftrifecta parse-translations parse-observation
  {:golden-path "test/golden/adapters-llm-parse.edn"
   :cases {:array [["a" "a"] false] :wrapped [["שלום"] true] :empty [[] false]}
   :gen (gen/tuple (gen/vector gen/string-alphanumeric 0 15) gen/boolean)
   :pred (fn [{[texts] :in out :out}] (and (m/validate p/TranslateResult out) (= texts (:ok out))))})
(deftest offline-llm-port
  (let [adapter (assoc (l/make-translator :venice {}) :api-key "fixture")]
    (with-redefs [chat/post-chat (fn [_ _ _ body _]
                                  {:ok (-> body (json/parse-string true) :messages second :content)})]
      (let [out (tr/translate-batch adapter ["one" "one" "世界"] "en" "he" {})]
        (is (m/validate p/TranslateResult out)) (is (= ["one" "one" "世界"] (:ok out)))))
    (with-redefs [chat/post-chat (fn [& _] {:ok "["})]
      (let [out (tr/translate-batch adapter ["one"] "en" "he" {})]
        (is (m/validate p/TranslateResult out)) (is (:error out))))))
