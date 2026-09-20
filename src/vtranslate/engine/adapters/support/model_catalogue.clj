(ns vtranslate.engine.adapters.support.model-catalogue
  "Which model each provider serves for each ROLE, and where that provider
   lives. One place, so refreshing a pin is one edit rather than five.

   Every LLM adapter in the engine and in vtranslate-context used to carry its
   own private copy of {:api-url :secret-env :secret-pass :model} per provider.
   Five copies drift, and they had: three namespaces still said glm-5.2 while
   the deployed worker config already asked for glm-5-3. A model id is a fact
   about a vendor's catalogue, not about the adapter that happens to call it,
   so it belongs here and the adapter reads it.

   What is a fact about the vendor lives here; what is a fact about one
   MACHINE does not. The endpoint and the conventional environment variable
   are the vendor's; the password-store path where a particular developer
   keeps the key is not, and comes from config through secret-pass-for.

   Nothing here is a decision, only a starting point. An operator's
   :translator-opts, :reviewer-opts, :comprehender-opts, :reader-opts or
   :perceiver-opts still override any of it key by key, which is what lets a
   deployment pin a model without waiting for a release.

   Pure data, no imports: core-safe, so it costs nothing to require.

   Pins are refreshed against the providers' own model endpoints
   (openrouter.ai/api/v1/models, api.venice.ai/api/v1/models), last on
   2026-09-20. A Venice model is audio-capable exactly when its
   capabilities.supportsAudioInput is true.")


(def providers
  "Where a provider lives, and the environment variable its key conventionally
   arrives in. Both are facts about the VENDOR, which is why they can be
   defaults at all.

   Deliberately absent: where the key is kept on any particular machine. A
   password-store path is a fact about one operator's setup, so it comes from
   config (see secret-pass-for) and this namespace has no opinion about it."
  {:openrouter {:api-url    "https://openrouter.ai/api/v1/chat/completions"
                :secret-env "OPENROUTER_API_KEY"}
   :venice     {:api-url    "https://api.venice.ai/api/v1/chat/completions"
                :secret-env "VENICE_API_KEY"}})

(def roles
  "The jobs a model is asked to do here, and what each one wants.

   :translate  the cues themselves, the bulk of the spend and of the quality.
   :review     a Y/N vote on one section, several votes per section.
   :comprehend the job's terms and names, read once from the transcript.
   :story      the narrative arc, read once over the whole transcript.
   :audio      a second listen at a stretch of the ACTUAL audio, so this role
               is servable only by a model that accepts an input_audio part."
  #{:translate :review :comprehend :story :audio})

(def models
  "provider -> role -> model id.

   Venice answers :review, :comprehend and :story with an UNCENSORED model on
   purpose: a reviewer that refuses the material cannot judge a translation of
   it, and a refusal would read as a failed review rather than as a verdict.

   :audio names the cheapest audio-capable model each provider serves. That
   matters because the audio expert is the expensive path: it is asked only
   about the stretches a reviewer condemned, and asking it about a healthy
   transcript costs nothing because it is never asked at all."
  {:openrouter {:translate  "z-ai/glm-5.3"
                :review     "z-ai/glm-5.3"
                :comprehend "z-ai/glm-5.3"
                :story      "deepseek/deepseek-v4-pro"
                :audio      "google/gemini-3.5-flash-lite"}
   :venice     {:translate  "z-ai-glm-5-3"
                :review     "gemma-4-uncensored"
                :comprehend "gemma-4-uncensored"
                :story      "gemma-4-uncensored"
                :audio      "gemini-3-5-flash-lite"}})

(defn model-for
  "The model `provider` serves for `role`, or nil when it serves none. Pure."
  [provider role]
  (get-in models [provider role]))

(defn defaults-for
  "The endpoint, key environment variable and model an adapter starts from to
   reach `provider` for `role`. nil when that provider serves no model for the
   role, so a caller cannot accidentally build an adapter with no model.
   Pure. => {:api-url s :secret-env s :model s} | nil"
  [provider role]
  (when-let [model (model-for provider role)]
    (assoc (get providers provider) :model model)))

(defn secret-pass-for
  "The password-store entry holding `provider`'s API key on THIS machine, read
   from the operator's config under :secrets, e.g.

     :secrets {:venice {:pass \"Venice/key\"}}

   There is no built-in default and there should not be: one developer's store
   is laid out nothing like another's, and a wrong path here is worse than no
   path, because resolve-key lets a pass entry win over the environment. A
   deployment that sets nothing simply authenticates from the environment,
   which is what the cluster does.
   Pure. => string | nil"
  [config provider]
  (get-in config [:secrets provider :pass]))

(defn provider-defaults
  "The map an adapter publishes as its own `provider-defaults`: every provider
   that serves `role`, keyed by provider. `key-fn` renames the keys for a
   registry that namespaces them by role, as the perceivers do.
   Pure. => {provider-key defaults}"
  ([role] (provider-defaults role identity))
  ([role key-fn]
   (reduce-kv (fn [acc provider _]
                (if-let [d (defaults-for provider role)]
                  (assoc acc (key-fn provider) d)
                  acc))
              {} providers)))
