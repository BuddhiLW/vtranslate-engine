(ns vtranslate.engine.providers.router-floor-test
  "LSP-floor for provider resolution (hive-mcp lsp_floor_test analogue). Pins the
   three rungs every resolver must honor: (1) NEVER nil, (2) correct outcome shape
   — ASR fails LOUD, MT degrades to the always-available :identity terminus, and
   (3) a resolved impl actually satisfies + behaviorally honors its protocol. Plus
   a property floor proving the :identity passthrough preserves count AND order,
   and an OCP check that a freshly-registered provider resolves."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hive-dsl.result :as r]
            [vtranslate.engine.contract.ports-contract :as contract]
            [vtranslate.engine.port.translator :as p.tr]
            [vtranslate.engine.providers.router :as router]
            [vtranslate.engine.providers.transcriber-registry :as tr-reg]
            [vtranslate.engine.providers.translator-registry :as tl-reg]
            ;; load the adapter so :identity self-registers its defmethod
            [vtranslate.engine.adapters.translator.identity]))

;; --- multimethod test hygiene: tear down throwaway registrations -----------

(use-fixtures :each
  (fn [t]
    (t)
    (remove-method tl-reg/resolve-translator :floor-test-throwaway)))

;; --- ASR resolver fails LOUD, never nil, never a fake ----------------------

(deftest transcriber-resolver-fails-loud
  (let [res (router/resolve-active-transcriber {:transcriber :nonsense} {})]
    (is (some? res) "resolver never returns nil")
    (is (r/err? res) "ASR resolver fails loud — no fake-default")
    (is (= :error/no-transcriber-available (:error res)))))

(deftest transcriber-registry-default-is-loud
  (let [res (tr-reg/resolve-transcriber :nonsense {})]
    (is (r/err? res))
    (is (= :error/unknown-transcriber (:error res)))
    (is (vector? (:known res)) "reports the known provider set")))

;; --- MT resolver degrades to the always-available :identity ----------------

(deftest translator-resolver-degrades-to-identity
  (let [res (router/resolve-active-translator {:translator :nonsense} {})]
    (is (some? res) "resolver never returns nil")
    (is (r/ok? res) "MT degrades to the :identity passthrough terminus")
    (is (satisfies? p.tr/ITranslator (:ok res)) "resolved value satisfies the protocol")
    (contract/check-translator (:ok res) ["x" "y"] "en" "pt-BR")))   ; behavior, not just type

(deftest translator-explicit-identity-resolves
  (let [res (router/resolve-active-translator {:translator :identity} {})]
    (is (r/ok? res))
    (contract/check-translator (:ok res) ["a" "b" "c"] "en" "pt-BR")))

;; --- property floor: :identity preserves count AND order (strict) ----------

(defspec identity-preserves-count-and-order 50
  (prop/for-all [texts (gen/vector gen/string-alphanumeric)]
    (let [tr  (:ok (router/resolve-active-translator {:translator :identity} {}))
          res (p.tr/translate-batch tr texts "en" "pt-BR" {})]
      (and (r/ok? res) (= texts (:ok res))))))

;; --- OCP: a new provider registers by adding a defmethod, no core edit ------

(deftest registering-a-provider-is-open-closed
  (defmethod tl-reg/resolve-translator :floor-test-throwaway
    [_ _config]
    (r/ok (reify p.tr/ITranslator
            (translate-batch [_ texts _ _ _] (r/ok (vec texts))))))
  (let [res (router/resolve-active-translator {:translator :floor-test-throwaway} {})]
    (is (r/ok? res) "freshly-registered provider resolves")
    (is (some #{:floor-test-throwaway} (tl-reg/known)) "appears in the known set")))
