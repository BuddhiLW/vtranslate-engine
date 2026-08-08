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
  "Where the user config file is read from.

   Precedence: explicit `override` > $VTRANSLATE_CONFIG > $XDG_CONFIG_HOME/
   vtranslate/config.edn > ~/.config/vtranslate/config.edn. An override or
   VTRANSLATE_CONFIG naming a path that does not exist is still honoured — an
   absent file resolves to built-in defaults, which is how a caller asks for
   ambient-free routing. => string"
  ([] (config-path nil))
  ([override]
   (or (not-empty override)
       (not-empty (System/getenv "VTRANSLATE_CONFIG"))
       (let [base (or (not-empty (System/getenv "XDG_CONFIG_HOME"))
                      (str (System/getProperty "user.home") "/.config"))]
         (str base "/vtranslate/config.edn")))))

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
   :fetcher          (src/coalesce [(src/env "VT_FETCHER")
                                    (src/file cfg-path [:providers :fetcher] :type :keyword)]
                                   :type :keyword :required false)
   :fetcher-opts     (src/file cfg-path [:fetcher-opts]
                               :type :map :required false :default {})
   :segmenter-opts   (src/file cfg-path [:segmenter-opts]
                               :type :map :required false :default {})
   :transcriber-opts (src/file cfg-path [:transcriber-opts]
                               :type :map :required false :default {})
   :translator-opts  (src/file cfg-path [:translator-opts]
                               :type :map :required false :default {})
   :composer-opts    (src/file cfg-path [:composer-opts]
                               :type :map :required false :default {})
   :addons           (src/file cfg-path [:addons]
                               :type :vector :required false :default [])})

(defn resolve-routing
  "Resolve active provider routing + option maps.
   `overrides` (job-spec :config) win over env/file/default for selected keys.
   `:config-path` in `overrides` names the config file to read instead of the
   ambient one; it selects the source and is never itself a routing key.
   => (r/ok {:segmenter kw :transcriber kw|nil :translator kw :composer kw
             :fetcher kw|nil :addons [] :segmenter-opts {} :transcriber-opts {}
             :translator-opts {} :composer-opts {} :fetcher-opts {}})
      | (r/err :config/resolution-failed {:errors [...] :partial {...}})."
  ([] (resolve-routing {}))
  ([overrides]
   (di/resolve-config (routing-fields (config-path (:config-path overrides)))
                      (select-keys overrides [:segmenter :transcriber :translator :composer
                                              :fetcher :segmenter-opts :transcriber-opts
                                              :translator-opts :composer-opts :fetcher-opts
                                              :addons]))))
