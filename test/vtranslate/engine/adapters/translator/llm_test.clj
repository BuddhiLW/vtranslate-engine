(ns vtranslate.engine.adapters.translator.llm-test
  "LlmTranslator pure surface (no network): parse-translations count/order guard
   (golden+property+mutation), prompt/body weaving, make-translator override
   precedence, and the no-key translate-batch error path."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [cheshire.core :as json]
            [hive-test.golden :refer [deftest-golden]]
            [hive-test.mutation :refer [deftest-mutations]]
            [hive-dsl.result :as r]
            [vtranslate.engine.adapters.support.llm-chat :as chat]
            [vtranslate.engine.port.translator :as p.tr]
            [vtranslate.engine.adapters.translator.llm :as llm]))

;; --- fixtures ---------------------------------------------------------------

(def ^:private suffix
  " GROUNDING (opaque suffix built behind the pipeline seam) — render Scales as Scales.")

(defn- parse-body [body] (json/parse-string body true))
(defn- sys-of [body] (-> (parse-body body) :messages first :content))
(defn- usr-of [body] (-> (parse-body body) :messages second :content))

;; =============================================================================
;; GOLDEN — parse-translations outcomes are plain maps (EDN-safe).
;; =============================================================================

(deftest-golden parse-translations-golden
  "test/golden/llm-parse-translations.edn"
  {:bare-array   (#'llm/parse-translations "[\"a\",\"b\",\"c\"]" 3)
   :single-key   (#'llm/parse-translations "{\"translations\":[\"x\",\"y\"]}" 2)
   :fenced       (#'llm/parse-translations "```json\n[\"p\",\"q\"]\n```" 2)
   :wrong-length (#'llm/parse-translations "[\"a\",\"b\"]" 3)
   :non-string   (#'llm/parse-translations "[1,2]" 2)
   :unparseable  (#'llm/parse-translations "not json at all" 2)})

;; GOLDEN — prompt/body strings are EDN-safe scalars/plain maps.

(deftest-golden prompt-golden
  "test/golden/llm-prompts.edn"
  {:system     (#'llm/system-prompt "en" "pt-BR" true suffix)
   :system-min (#'llm/system-prompt nil "pt-BR" false nil)
   :context    (#'llm/context-block "PRECEDING CONTEXT" ["l1" "l2"])
   :context-nil (#'llm/context-block "X" [])
   :user       (#'llm/user-content ["a" "b"] ["ctx"] nil)
   :body       (parse-body (#'llm/chat-body "model-x" "en" "pt-BR" ["a" "b"]
                                            {:context/before ["pre"] :context/after ["post"]
                                             :prompt/system-suffix suffix}))})

;; =============================================================================
;; PROPERTY — the count/order contract.
;; =============================================================================

(def ^:private gen-strvec (gen/vector gen/string-ascii 0 12))

;; A JSON array of n strings round-trips to (r/ok that-exact-vector).
(defspec parse-roundtrips-exact-vector 300
  (prop/for-all [v gen-strvec]
    (= (r/ok v)
       (#'llm/parse-translations (json/generate-string v) (count v)))))

;; A single-key object wrapping the n-string array also parses to (r/ok vec).
(defspec parse-accepts-single-key-object 200
  (prop/for-all [v gen-strvec]
    (= (r/ok v)
       (#'llm/parse-translations
        (json/generate-string {:translations v}) (count v)))))

;; Any length mismatch is a loud (r/err :error/translation-failed).
(defspec parse-wrong-length-always-errs 300
  (prop/for-all [v     gen-strvec
                 delta (gen/choose 1 5)]
    (let [res (#'llm/parse-translations (json/generate-string v) (+ (count v) delta))]
      (and (r/err? res)
           (= :error/translation-failed (:error res))
           (= (+ (count v) delta) (:expected res))))))

;; n-strings? is exactly: sequential AND length-n AND all strings.
(defspec n-strings-predicate 300
  (prop/for-all [v gen-strvec
                 n (gen/choose 0 15)]
    (= (boolean (#'llm/n-strings? v n))
       (= n (count v)))))

;; =============================================================================
;; parse-translations — focused shape assertions
;; =============================================================================

(deftest parse-translations-cases
  (is (= (r/ok ["a" "b"]) (#'llm/parse-translations "[\"a\",\"b\"]" 2))
      "bare JSON array of n strings")
  (is (= (r/ok ["x" "y"]) (#'llm/parse-translations "{\"translations\":[\"x\",\"y\"]}" 2))
      "single-key wrapper object")
  (is (= (r/ok ["p" "q"]) (#'llm/parse-translations "```json\n[\"p\",\"q\"]\n```" 2))
      "strips triple-backtick fences")
  (let [e (#'llm/parse-translations "[\"a\"]" 3)]
    (is (r/err? e))
    (is (= :error/translation-failed (:error e)))
    (is (= 3 (:expected e)) "wrong length reports :expected n"))
  (let [e (#'llm/parse-translations "[1,2]" 2)]
    (is (r/err? e))
    (is (= :error/translation-failed (:error e)))
    (is (= 2 (:expected e)) "wrong-shape reports :expected too")
    (is (str/includes? (:reason e) "wrong shape")))
  (let [e (#'llm/parse-translations "definitely not json" 1)]
    (is (r/err? e))
    (is (= :error/translation-failed (:error e)))
    (is (= "unparseable model output" (:reason e))))
  ;; a two-key object is NOT a valid single-key wrapper
  (is (r/err? (#'llm/parse-translations "{\"a\":[\"x\"],\"b\":1}" 1))
      "only a single-key object is unwrapped"))

;; =============================================================================
;; chat-body — opts weaving into the produced request body.
;; =============================================================================

(deftest chat-body-weaves-context-and-suffix
  (let [body (#'llm/chat-body "M" "en" "pt-BR" ["one" "two"]
                              {:context/before ["Prev line"]
                               :context/after  ["Next line"]
                               :prompt/system-suffix suffix})
        sys  (sys-of body)
        usr  (usr-of body)
        p    (parse-body body)]
    (is (= "M" (:model p)))
    (is (= 2 (count (:messages p))))
    (is (= "system" (:role (first (:messages p)))))
    (is (= "user" (:role (second (:messages p)))))
    ;; the opaque suffix is appended verbatim to the SYSTEM prompt
    (is (str/includes? sys suffix))
    ;; context present => the do-not-translate context clause appears
    (is (str/includes? sys "PRECEDING CONTEXT / FOLLOWING CONTEXT"))
    ;; context blocks + the payload array woven into the USER message
    (is (str/includes? usr "PRECEDING CONTEXT:"))
    (is (str/includes? usr "Prev line"))
    (is (str/includes? usr "FOLLOWING CONTEXT:"))
    (is (str/includes? usr "Next line"))
    (is (str/includes? usr "[\"one\",\"two\"]"))))

(deftest chat-body-without-suffix-omits-it
  (let [sys (sys-of (#'llm/chat-body "M" "en" "pt-BR" ["x"] {}))]
    (is (not (str/includes? sys suffix)) "no :prompt/system-suffix => nothing appended")))

(deftest chat-body-no-context-omits-context-clause
  (let [sys (sys-of (#'llm/chat-body "M" "en" "pt-BR" ["x"] {}))]
    (is (not (str/includes? sys "PRECEDING CONTEXT / FOLLOWING CONTEXT"))
        "no context opts => no context clause")))

;; =============================================================================
;; make-translator — provider defaults vs [:translator-opts] overrides.
;; =============================================================================

(deftest make-translator-uses-provider-defaults
  (let [t (llm/make-translator :openrouter {})]
    (is (= "https://openrouter.ai/api/v1/chat/completions" (:api-url t)))
    (is (= "z-ai/glm-5.2" (:model t)))
    (is (= "OPENROUTER_API_KEY" (:secret-env t)))
    (is (= "openrouter/keys/hive-mcp" (:secret-pass t))))
  (let [t (llm/make-translator :venice {})]
    (is (= "https://api.venice.ai/api/v1/chat/completions" (:api-url t)))
    (is (= "zai-org-glm-5-2" (:model t)))
    (is (= "VENICE_API_KEY" (:secret-env t)))
    (is (= "Venice/api-key" (:secret-pass t)))))

(deftest make-translator-opts-override-each-field
  (let [t (llm/make-translator :openrouter
                               {:translator-opts {:api-url "http://x/y"
                                                  :model "m2"
                                                  :secret-env "E2"
                                                  :secret-pass "p2"}})]
    (is (= "http://x/y" (:api-url t)))
    (is (= "m2" (:model t)))
    (is (= "E2" (:secret-env t)))
    (is (= "p2" (:secret-pass t)))))

(deftest make-translator-secret-pass-only-overridden-when-key-present
  (let [t (llm/make-translator :openrouter {:translator-opts {:model "m2"}})]
    (is (= "openrouter/keys/hive-mcp" (:secret-pass t))
        "absent :secret-pass key => default pass survives"))
  (let [t (llm/make-translator :openrouter {:translator-opts {:secret-pass nil}})]
    (is (nil? (:secret-pass t))
        "explicit nil :secret-pass overrides the default (key present)")))

;; =============================================================================
;; translate-batch — no-network paths only.
;; =============================================================================

(deftest translate-batch-empty-is-ok-empty
  (let [t (llm/make-translator :openrouter {})]
    (is (= (r/ok []) (p.tr/translate-batch t [] "en" "pt-BR" {}))
        "empty input short-circuits before any key lookup / network")))

(deftest translate-batch-no-key-fails-loud
  (let [t   (llm/make-translator :openrouter
                                 {:translator-opts {:secret-env "VT_NO_SUCH_KEY_XYZ"
                                                    :secret-pass nil}})
        res (p.tr/translate-batch t ["hello"] "en" "pt-BR" {})]
    (is (r/err? res) "no key anywhere => loud error, no network")
    (is (= :error/translation-failed (:error res)))
    (is (str/includes? (:reason res) "no API key"))
    (is (= "VT_NO_SUCH_KEY_XYZ" (subs (:reason res) (- (count (:reason res))
                                                       (count "VT_NO_SUCH_KEY_XYZ"))))
        "the failing env var name is reported")))

;; =============================================================================
;; MUTATION — the count/order guard and its predicate.
;; =============================================================================

(defn- parse-guard-assertions []
  ;; every parse call sits inside `is` so a throwing mutant is caught as :error.
  (is (= (r/ok ["a" "b"]) (#'llm/parse-translations "[\"a\",\"b\"]" 2)))
  (is (r/err? (#'llm/parse-translations "[\"a\",\"b\"]" 3)))
  (is (= :error/translation-failed (:error (#'llm/parse-translations "[\"a\",\"b\"]" 3))))
  (is (r/err? (#'llm/parse-translations "[1,2]" 2)))
  (is (= :error/translation-failed (:error (#'llm/parse-translations "[1,2]" 2))))
  (is (r/err? (#'llm/parse-translations "nope" 1)))
  (is (= :error/translation-failed (:error (#'llm/parse-translations "nope" 1)))))

(deftest-mutations parse-translations-mutations-caught
  vtranslate.engine.adapters.translator.llm/parse-translations
  [["accept-any-length"
    (fn [content _n]
      (r/ok (vec (json/parse-string (chat/strip-fences content)))))]
   ["ignore-string-check"
    (fn [content n]
      (let [p (json/parse-string (chat/strip-fences content))]
        (if (and (sequential? p) (= n (count p)))
          (r/ok (vec p))
          (r/err :error/translation-failed {:expected n}))))]
   ["wrong-error"
    (fn [content n]
      (let [p (try (json/parse-string (chat/strip-fences content))
                   (catch Exception _ ::bad))]
        (if (and (sequential? p) (= n (count p)) (every? string? p))
          (r/ok (vec p))
          (r/err :error/some-other-thing {:expected n}))))]]
  parse-guard-assertions)

(defn- n-strings-assertions []
  (is (true?  (boolean (#'llm/n-strings? ["a" "b"] 2))))
  (is (false? (boolean (#'llm/n-strings? ["a" "b"] 3))))
  (is (false? (boolean (#'llm/n-strings? [1 2] 2))))
  (is (false? (boolean (#'llm/n-strings? "ab" 2))))
  (is (true?  (boolean (#'llm/n-strings? [] 0)))))

(deftest-mutations n-strings-mutations-caught
  vtranslate.engine.adapters.translator.llm/n-strings?
  [["ignore-count"  (fn [v _n] (and (sequential? v) (every? string? v)))]
   ["ignore-string" (fn [v n] (and (sequential? v) (= n (count v))))]
   ["always-true"   (fn [_v _n] true)]]
  n-strings-assertions)
