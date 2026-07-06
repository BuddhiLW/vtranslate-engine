(ns vtranslate.engine.providers.config
  "L2 — provider ROUTING resolution: WHICH asr/mt provider is active, plus the
   provider option maps passed to adapter factories. Turning provider keywords
   into impls is the registries job (L3).

   Precedence (first non-nil wins): caller overrides (the job-spec :config) > env
   provider selectors (VT_SEGMENTER / VT_TRANSCRIBER / VT_TRANSLATOR) >
   ~/.config/vtranslate/config.edn > built-in defaults. hive-di does typed read +
   coalesce; we require hive-di.source + hive-di.resolve DIRECTLY (not
   hive-di.core) so the engine never pulls Malli in via defconfig schema generation.

   Mirrors hive-mcp embeddings/env_config split: hive-di resolves data, bespoke
   registries instantiate providers."
  (:require [hive-di.source :as src]
            [hive-di.resolve :as di]))

(defn config-path
  "XDG-correct user-config path: $XDG_CONFIG_HOME/vtranslate/config.edn, else
   ~/.config/vtranslate/config.edn (better than hive-mcp hardcoded ~/.config)."
  []
  (let [base (or (not-empty (System/getenv "XDG_CONFIG_HOME"))
                 (str (System/getProperty "user.home") "/.config"))]
    (str base "/vtranslate/config.edn")))

(defn- routing-fields
  "Field registry: provider selectors coalesce env > config.edn > default.
   Option maps come from config.edn or caller overrides only; secrets are refs
   like `:secret-pass`, never literal secret values."
  [cfg-path]
  {:segmenter        (src/coalesce [(src/env "VT_SEGMENTER")
                                    (src/file cfg-path [:providers :segmenter] :type :keyword)]
                                   :type :keyword :required false :default :grid)
   :transcriber      (src/coalesce [(src/env "VT_TRANSCRIBER")
                                    (src/file cfg-path [:providers :transcriber] :type :keyword)]
                                   :type :keyword :required false)
   :translator       (src/coalesce [(src/env "VT_TRANSLATOR")
                                    (src/file cfg-path [:providers :translator] :type :keyword)]
                                   :type :keyword :required false)
   :composer         (src/coalesce [(src/env "VT_COMPOSER")
                                    (src/file cfg-path [:providers :composer] :type :keyword)]
                                   :type :keyword :required false :default :none)
   :segmenter-opts   (src/file cfg-path [:segmenter-opts]
                               :type :map :required false :default {})
   :transcriber-opts (src/file cfg-path [:transcriber-opts]
                               :type :map :required false :default {})
   :translator-opts  (src/file cfg-path [:translator-opts]
                               :type :map :required false :default {})
   :composer-opts    (src/file cfg-path [:composer-opts]
                               :type :map :required false :default {})})

(defn resolve-routing
  "Resolve active provider routing + option maps.
   `overrides` (job-spec :config) win over env/file/default for selected keys.
   => (r/ok {:segmenter kw :transcriber kw|nil :translator kw :composer kw
             :segmenter-opts {} :transcriber-opts {} :translator-opts {}
             :composer-opts {}})
      | (r/err :config/resolution-failed {:errors [...] :partial {...}})."
  ([] (resolve-routing {}))
  ([overrides]
   (di/resolve-config (routing-fields (config-path))
                      (select-keys overrides [:segmenter :transcriber :translator :composer
                                              :segmenter-opts :transcriber-opts
                                              :translator-opts :composer-opts]))))
