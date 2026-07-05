(ns vtranslate.engine.providers.config
  "L2 — provider ROUTING resolution: WHICH asr/mt provider is active, as a plain
   typed map. Turning that keyword into an impl is the registries' job (L3).

   Precedence (first non-nil wins): caller overrides (the job-spec :config) > env
   (VT_TRANSCRIBER / VT_TRANSLATOR) > ~/.config/vtranslate/config.edn
   [:providers ...] > built-in default. hive-di does the typed read + coalesce;
   we require hive-di.source + hive-di.resolve DIRECTLY (not hive-di.core) so the
   engine never pulls Malli in via defconfig's schema generation.

   Mirrors hive-mcp's embeddings/env_config split: hive-di resolves the value,
   a bespoke registry instantiates it."
  (:require [hive-di.source :as src]
            [hive-di.resolve :as di]))

(defn config-path
  "XDG-correct user-config path: $XDG_CONFIG_HOME/vtranslate/config.edn, else
   ~/.config/vtranslate/config.edn (better than hive-mcp's hardcoded ~/.config)."
  []
  (let [base (or (not-empty (System/getenv "XDG_CONFIG_HOME"))
                 (str (System/getProperty "user.home") "/.config"))]
    (str base "/vtranslate/config.edn")))

(defn- routing-fields
  "Field registry: each provider key coalesces env > config.edn > (literal default).
   :type :keyword — NOT :enum, since file sources reject :allowed; the allowed-set
   is enforced downstream by the registries' :default method (fail-loud).

   :transcriber has NO default: absent => nil => the router fails loud (there is
   no valid fake transcript). :translator defaults to :identity, the always-
   available passthrough terminus."
  [cfg-path]
  {:transcriber (src/coalesce [(src/env "VT_TRANSCRIBER")
                               (src/file cfg-path [:providers :transcriber] :type :keyword)]
                              :type :keyword :required false)
   :translator  (src/coalesce [(src/env "VT_TRANSLATOR")
                               (src/file cfg-path [:providers :translator] :type :keyword)]
                              :type :keyword :required false :default :identity)})

(defn resolve-routing
  "Resolve the active provider routing.
   `overrides` (the job-spec :config) win over env/file/default; only its
   :transcriber / :translator keys are consulted.
   => (r/ok {:transcriber kw|nil :translator kw})
      | (r/err :config/resolution-failed {:errors [...] :partial {...}})."
  ([] (resolve-routing {}))
  ([overrides]
   (di/resolve-config (routing-fields (config-path))
                      (select-keys overrides [:transcriber :translator]))))
