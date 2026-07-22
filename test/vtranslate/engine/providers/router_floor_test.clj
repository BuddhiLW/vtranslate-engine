(ns vtranslate.engine.providers.router-floor-test
  "LSP-floor for provider resolution (hive-mcp lsp_floor_test analogue). Pins the
   three rungs every resolver must honor: (1) NEVER nil, (2) correct outcome shape
   — ASR fails LOUD, MT fails LOUD without an explicit provider, and
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

(deftest transcriber-resolver-never-fakes
  (let [res (router/resolve-active-transcriber {:transcriber :nonsense} {})]
    (is (some? res) "resolver never returns nil")
    (if (r/ok? res)
      (is (not (instance? vtranslate.engine.adapters.transcriber.stub.StubTranscriber (:ok res)))
          "ASR resolver may pick a real configured provider, but never the stub")
      (do
        (is (= :error/no-transcriber-available (:error res)))
        (is (some #{:nonsense} (:tried res)))))))

(deftest transcriber-registry-default-is-loud
  (let [res (tr-reg/resolve-transcriber :nonsense {})]
    (is (r/err? res))
    (is (= :error/unknown-transcriber (:error res)))
    (is (vector? (:known res)) "reports the known provider set")))

;; --- observability: candidate errors accumulate; unknown vs failed distinct ---

(deftest unknown-requested-provider-surfaces-in-errors
  ;; translator priority is empty, so a bad requested key exhausts deterministically:
  ;; the unknown key is reported first, as :error/unknown-translator, never masked.
  (let [res  (router/resolve-active-translator {:translator :zzz-unknown} {})
        errs (:errors res)]
    (is (r/err? res))
    (is (= :error/no-translator-available (:error res)))
    (is (= [:zzz-unknown :error/unknown-translator] (first errs)))))

(deftest resolver-distinguishes-build-error-from-unknown
  ;; a REGISTERED-but-misconfigured provider surfaces its own build error — the
  ;; whole point: 'registered but broken' must not read as 'unknown provider'.
  (defmethod tl-reg/resolve-translator :floor-broken
    [_ _config] (r/err :error/translator-unavailable {:reason "misconfigured"}))
  (try
    (let [res  (router/resolve-active-translator {:translator :floor-broken} {})
          errs (:errors res)]
      (is (r/err? res))
      (is (= [:floor-broken :error/translator-unavailable] (first errs)))
      (is (not= :error/unknown-translator (second (first errs)))))
    (finally (remove-method tl-reg/resolve-translator :floor-broken))))

;; --- selection order: requested-first, priority chain, de-duplicated ----------

(defspec transcriber-order-requested-first-and-deduped 100
  ;; order-for is tested directly so it does not depend on which adapters happen
  ;; to be registered in the test image (some resolve, so the chain need not exhaust).
  (prop/for-all [requested (gen/elements [nil :zzz :whisper-local :sherpa-onnx :whisper-server])]
    (let [order (#'router/order-for requested router/transcriber-priority)]
      (and (= order (distinct order))                                   ;; de-duplicated
           (if requested (= requested (first order)) true)              ;; requested first
           (= (vec (distinct (remove nil? (cons requested router/transcriber-priority))))
              order)))))                                                 ;; exact ordered chain

;; --- MT fails LOUD without explicit provider -------------------------------

(deftest translator-resolver-fails-loud-without-explicit-provider
  (let [res (router/resolve-active-translator {:translator :nonsense} {})]
    (is (some? res) "resolver never returns nil")
    (is (r/err? res))
    (is (= :error/no-translator-available (:error res)))
    (is (= {:requested :nonsense
            :tried [:nonsense]}
           (select-keys res [:requested :tried])))))   ; behavior, not just type

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