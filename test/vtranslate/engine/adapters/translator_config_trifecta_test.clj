(ns vtranslate.engine.adapters.translator-config-trifecta-test
  "Build-time key attachment for the LLM translator, the shared chat
   transport's timeout and key readers, the catalogue's role set, and the
   transcript cache's location and envelope keys. Key resolution is stubbed,
   so neither the environment nor a password store is consulted; no request is
   sent."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.adapters.support.llm-chat :as chat]
            [vtranslate.engine.adapters.support.model-catalogue :as cat]
            [vtranslate.engine.adapters.support.secrets :as secrets]
            [vtranslate.engine.adapters.transcript-cache.expiring :as exp]
            [vtranslate.engine.adapters.transcript-cache.fressian-file :as ff]
            [vtranslate.engine.adapters.translator.llm :as llm]
            [vtranslate.engine.providers.translator-registry :as reg]
            [vtranslate.engine.schema.adapters-gates :as g]))

(defn resolved-observation
  "Attach a key to a translator built by the real constructor. The key store
   answers `key` for any lookup, or nothing when `key` is nil."
  [[provider opts key :as in]]
  (with-redefs-fn {#'chat/resolve-key (fn [env pass] (when key (str key "|" env "|" pass)))}
    #(let [res (llm/resolved (llm/make-translator provider {:translator-opts opts}))]
       {:in in :out (cond-> res (:ok res) (update :ok (fn [t] (into {} t))))})))

(deftrifecta resolved resolved-observation
  {:golden-path "test/golden/adapters-llm-resolved.edn"
   :cases {:venice-key [:venice {} "k"]
           :openrouter-pass [:openrouter {:secret-pass "Org/key"} "k"]
           :no-key [:venice {} nil]
           :no-key-pass [:openrouter {:secret-pass "Org/key"} nil]}
   :gen (gen/tuple (gen/elements [:venice :openrouter])
                   (gen/let [env (gen/elements [nil "X_KEY"]) pass (gen/elements [nil "a/b"])]
                     (cond-> {} env (assoc :secret-env env) pass (assoc :secret-pass pass)))
                   (gen/one-of [(gen/return nil) gen/string-alphanumeric]))
   :pred (fn [{[provider opts key] :in out :out}]
           (let [env (or (:secret-env opts) (get-in cat/providers [provider :secret-env]))
                 pass (:secret-pass opts)]
             (if key
               (and (m/validate g/ResolvedTranslator (:ok out))
                    (= (str key "|" env "|" pass) (get-in out [:ok :api-key]))
                    (= provider (get-in out [:ok :provider]))
                    (= (cat/model-for provider :translate) (get-in out [:ok :model])))
               (and (m/validate g/TranslatorUnavailable out)
                    (= env (:secret-env out)) (= pass (:secret-pass out))
                    (str/includes? (:hint out) env)
                    (= (some? pass) (str/includes? (:hint out) "pass insert"))))))})

(deftest registry-resolves-through-resolved
  (with-redefs-fn {#'chat/resolve-key (constantly nil)}
    (fn []
      (doseq [k [:venice :openrouter]]
        (is (m/validate g/TranslatorUnavailable (reg/resolve-translator k {})))))))

(deftest chat-transport-readers
  (testing "resolve-key is the shared secrets resolver, not a copy"
    (is (identical? secrets/resolve-key chat/resolve-key)))
  (testing "the default timeout is generous and is what an unset env yields"
    (is (= 300 chat/default-request-timeout-s))
    (is (m/validate g/RequestTimeoutS (chat/request-timeout-s)))
    (when-not (System/getenv "VT_LLM_TIMEOUT_S")
      (is (= chat/default-request-timeout-s (chat/request-timeout-s))))))

(deftest catalogue-roles-cover-every-model-pin
  (is (m/validate g/CatalogueRoles cat/roles))
  (is (= cat/roles (set (mapcat keys (vals cat/models)))))
  (testing "every role is servable by some provider"
    (doseq [role cat/roles]
      (is (seq (cat/provider-defaults role)) (str role)))))

(deftest fressian-cache-location
  (let [dir (ff/default-dir)
        base (or (not-empty (System/getenv "XDG_CACHE_HOME"))
                 (str (System/getProperty "user.home") "/.cache"))]
    (is (m/validate g/CacheDir dir))
    (is (= (str base "/vtranslate/transcripts") dir))
    (testing "make-cache with no or blank dir falls back to the XDG location"
      (is (= dir (:dir (ff/make-cache))))
      (is (= dir (:dir (ff/make-cache ""))))
      (is (= "/tmp/x" (:dir (ff/make-cache "/tmp/x")))))))

(deftest expiring-envelope-keys
  (is (= :transcript-cache/transcript exp/transcript-key))
  (is (not= exp/transcript-key exp/cached-at-key))
  (is (every? #(= "transcript-cache" (namespace %)) [exp/transcript-key exp/cached-at-key]))
  (is (= {:segments []} (exp/unwrap {exp/cached-at-key 1 exp/transcript-key {:segments []}}))))
