(ns vtranslate.engine.adapters.translator.llm
  "LLM translator over any OpenAI-compatible /chat/completions endpoint
   (OpenRouter, Venice, OpenAI, Groq, ...). ONE record parameterized by base URL,
   model, and where the API key comes from; :openrouter and :venice register
   themselves (OCP — adding another OpenAI-compatible host is one more defmethod).

   Order/count-preserving (the ITranslator contract): it asks the model for a JSON
   array of translations 1:1 with the input segments and VALIDATES the length,
   failing LOUD (:error/translation-failed) on any mismatch — never silently
   drops/reorders.

   Secret resolution (never stored in config/spec): a configured `pass:` path wins
   (the authoritative source, matching hive-mcp), else the named env var. So a
   stale env key cannot shadow the real key; a host without `pass` degrades to env.
   Choosing this provider with NO key anywhere yields a loud translation error at
   run, not a silent passthrough."
  (:require [cheshire.core :as json]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [hive-dsl.result :as r]
            [vtranslate.engine.port.translator :as p.tr]
            [vtranslate.engine.providers.translator-registry :as reg])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers)
           (java.time Duration)))

;; --- secret resolution: pass: ref (authoritative) > env var ----------------

(defn- pass-show
  "First line of `pass show <path>`, or nil (missing pass / non-zero exit)."
  [path]
  (try
    (let [{:keys [exit out]} (shell/sh "pass" "show" path)]
      (when (zero? exit) (some-> out str/split-lines first str/trim not-empty)))
    (catch Exception _ nil)))

(defn- resolve-key
  "A configured `pass:` path wins over the env var (a stale env key must not
   shadow the real one). => key-string | nil."
  [secret-env secret-pass]
  (or (when secret-pass (pass-show secret-pass))
      (some-> (System/getenv secret-env) str/trim not-empty)))

;; --- HTTP / prompt ----------------------------------------------------------

(def ^:private http-client
  (delay (.. (HttpClient/newBuilder) (connectTimeout (Duration/ofSeconds 15)) (build))))

(defn- system-prompt
  "System instruction to translate a JSON array of subtitle strings from `src` to
   `tgt`, returning a JSON array of the same length and order. When `context?` is
   truthy, also instructs the model to treat labelled surrounding lines as
   untranslated reference context."
  [src tgt context?]
  (str "You are a professional subtitle translator. The user message is a JSON "
       "array of subtitle strings. Translate each element from "
       (or src "its source language") " to " tgt ". Keep meaning, tone, and a "
       "subtitle-appropriate length. Return ONLY a JSON array of translated "
       "strings the SAME length and order as the input. No prose, no markdown, "
       "no code fences."
       (when context?
         (str " Lines under PRECEDING CONTEXT / FOLLOWING CONTEXT are reference "
              "only do NOT translate them and do NOT include them in your output."))))

(defn- context-block
  "Render `label` over `lines` as a text block, or nil when `lines` is empty."
  [label lines]
  (when (seq lines)
    (str label ":\n" (str/join "\n" lines) "\n\n")))

(defn- user-content
  "The user message: PRECEDING/FOLLOWING context blocks (when present) followed by
   the JSON array of `texts` to translate."
  [texts before after]
  (str (context-block "PRECEDING CONTEXT" before)
       (context-block "FOLLOWING CONTEXT" after)
       (json/generate-string (vec texts))))

(defn- chat-body
  "Build the chat-completions request body translating `texts` from `src` to
   `tgt`, using opts :context/before and :context/after as reference context."
  [model src tgt texts {:context/keys [before after]}]
  (json/generate-string
   {:model       model
    :temperature 0.2
    :messages    [{:role "system" :content (system-prompt src tgt (boolean (or (seq before) (seq after))))}
                  {:role "user"   :content (user-content texts before after)}]}))

(defn- strip-fences [s]
  (-> (str/trim (str s))
      (str/replace #"^```(?:json)?\s*" "")
      (str/replace #"\s*```$" "")))

(defn- post-chat
  "POST the chat request, returning the assistant message content.
   => (r/ok content-string) | (r/err :error/translation-failed {...})."
  [api-url api-key body]
  (r/try-effect* :error/translation-failed
    (let [req  (.. (HttpRequest/newBuilder (URI/create api-url))
                   (timeout (Duration/ofSeconds 60))
                   (header "Content-Type" "application/json")
                   (header "Authorization" (str "Bearer " api-key))
                   (POST (HttpRequest$BodyPublishers/ofString body))
                   (build))
          resp (.send ^HttpClient @http-client req (HttpResponse$BodyHandlers/ofString))
          code (.statusCode resp)
          pay  (.body resp)]
      (if (<= 200 code 299)
        (-> (json/parse-string pay true) :choices first :message :content)
        (throw (ex-info (str "chat HTTP " code) {:status code}))))))

(defn- n-strings?
  "True when `v` is a sequential of exactly `n` strings."
  [v n]
  (and (sequential? v) (= n (count v)) (every? string? v)))

(defn- parse-translations
  "Parse the model's reply into a vector of exactly `n` translated strings.
   Accepts a bare JSON array (the contract), or a single-key object wrapping the
   array (e.g. {\"translations\": [...]}). => (r/ok [...]) | (r/err ...)."
  [content n]
  (let [parsed (try (json/parse-string (strip-fences content)) (catch Exception _ ::bad))
        arr    (cond
                 (n-strings? parsed n) (vec parsed)
                 (and (map? parsed) (= 1 (count parsed)) (n-strings? (first (vals parsed)) n))
                 (vec (first (vals parsed))))]
    (cond
      (= parsed ::bad) (r/err :error/translation-failed {:reason "unparseable model output"})
      (some? arr)      (r/ok arr)
      :else            (r/err :error/translation-failed
                              {:reason "model returned wrong shape/length" :expected n}))))

(defrecord LlmTranslator [api-url model secret-env secret-pass]
  p.tr/ITranslator
  (translate-batch [_ texts source-language target-language opts]
    (if (empty? texts)
      (r/ok [])
      (if-let [api-key (resolve-key secret-env secret-pass)]
        (r/let-ok [content (post-chat api-url api-key
                                      (chat-body model source-language target-language texts opts))]
          (parse-translations content (count texts)))
        (r/err :error/translation-failed
               {:reason (str "no API key set env " secret-env
                             (when secret-pass (str " or pass " secret-pass)))
                :api-url api-url})))))

(def ^:private provider-defaults
  "Self-contained per-provider endpoint, model, and key sources."
  {:openrouter {:api-url     "https://openrouter.ai/api/v1/chat/completions"
                :secret-env  "OPENROUTER_API_KEY"
                :secret-pass "openrouter/keys/hive-mcp"
                :model       "z-ai/glm-5.2"}
   :venice     {:api-url     "https://api.venice.ai/api/v1/chat/completions"
                :secret-env  "VENICE_API_KEY"
                :secret-pass "Venice/api-key"
                :model       "zai-org-glm-5-2"}})

(defn make-translator
  "Build an LLM translator for `provider-key`. Per-provider overrides (api-url /
   model / secret-env / secret-pass) may be supplied under config
   [:translator-opts] (a map); absent => the built-in provider defaults. NOTE:
   the [:translator] key itself is the routing SELECTION (a provider keyword), not
   an opts map — opts live under [:translator-opts] to avoid that collision."
  [provider-key config]
  (let [d    (get provider-defaults provider-key)
        opts (get config :translator-opts)]
    (->LlmTranslator (or (:api-url opts) (:api-url d))
                     (or (:model opts) (:model d))
                     (or (:secret-env opts) (:secret-env d))
                     (if (and (map? opts) (contains? opts :secret-pass))
                       (:secret-pass opts)
                       (:secret-pass d)))))

(defmethod reg/resolve-translator :openrouter [k config] (r/ok (make-translator k config)))
(defmethod reg/resolve-translator :venice     [k config] (r/ok (make-translator k config)))