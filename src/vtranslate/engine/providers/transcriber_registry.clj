(ns vtranslate.engine.providers.transcriber-registry
  "L3 — transcriber (ASR) provider registry. An OPEN multimethod factory: each
   adapter ns self-registers a (defmethod resolve-transcriber <provider-key> ...)
   so adding a provider never edits this file (OCP — mirrors wiring/build-port one
   level deeper). Cacheless/pure: a config swap is honored on the next resolve,
   no provider-cache to invalidate (avoids hive-mcp's stale-cache footgun).

   resolve-transcriber => (r/ok <ITranscriber impl>) | (r/err ...). The :default
   method fails LOUD with the known provider set — never a silent fallback, never
   a fake transcriber."
  (:require [hive-dsl.result :as r]))

(defmulti resolve-transcriber
  "Build an ITranscriber for `provider-key`, reading any needed opts from `config`.
   Capability gating (binary/key presence) belongs in each adapter's defmethod,
   returning (r/err :error/transcriber-unavailable ...) when its backend is absent."
  (fn [provider-key _config] provider-key))

(defn known
  "Registered provider keys, excluding the :default fallthrough."
  []
  (vec (remove #{:default} (keys (methods resolve-transcriber)))))

(defmethod resolve-transcriber :default
  [provider-key _config]
  (r/err :error/unknown-transcriber
         {:provider-key provider-key
          :known        (known)
          :hint         "set config [:providers :transcriber] (or VT_TRANSCRIBER) to a known key, or load an adapter ns that registers it"}))
