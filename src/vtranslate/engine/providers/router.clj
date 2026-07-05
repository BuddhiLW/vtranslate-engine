(ns vtranslate.engine.providers.router
  "L4 — active-provider resolution + fallback POLICY (SRP: routing decisions live
   here; instantiation lives in the L3 registries). Given resolved routing, pick a
   provider: the requested key first, then an ordered priority chain.

   Fail-loud discipline (mirrors hive-mcp escalate.clj): transcription has NO valid
   degenerate impl, so an exhausted chain returns
   (r/err :error/no-transcriber-available) — NEVER a fake transcript. Translation
   MAY degrade to the always-available :identity passthrough terminus."
  (:require [hive-dsl.result :as r]
            [vtranslate.engine.providers.transcriber-registry :as tr-reg]
            [vtranslate.engine.providers.translator-registry :as tl-reg]))

(def transcriber-priority
  "Ordered ASR fallthrough when the requested provider can't be built. These keys
   are served by real adapters (M3); until then the chain legitimately exhausts
   and the resolver fails loud."
  [:whisper-local :openai-whisper])

(def translator-priority
  "Ordered MT fallthrough; ENDS in :identity (always-available passthrough)."
  [:identity])

(defn- first-ok
  "Try each provider key in order via `resolve-fn`; the first (r/ok ...) wins,
   else nil (the caller turns an exhausted chain into a loud err)."
  [resolve-fn keys config]
  (some (fn [k] (let [res (resolve-fn k config)] (when (r/ok? res) res)))
        keys))

(defn- order-for
  "Requested key first (when set), then the priority chain, de-duplicated."
  [requested priority]
  (vec (distinct (remove nil? (cons requested priority)))))

(defn resolve-active-transcriber
  "Resolve an ITranscriber: requested key first, then `transcriber-priority`.
   `routing` = {:transcriber kw|nil ...}; `config` carries adapter opts.
   => (r/ok impl) | (r/err :error/no-transcriber-available {:requested :tried})."
  [routing config]
  (let [order (order-for (:transcriber routing) transcriber-priority)]
    (or (first-ok tr-reg/resolve-transcriber order config)
        (r/err :error/no-transcriber-available
               {:requested (:transcriber routing)
                :tried     order
                :hint      "configure an available ASR provider; ASR never falls back to a fake transcript"}))))

(defn resolve-active-translator
  "Resolve an ITranslator: requested key first, then `translator-priority`
   (ending in :identity — MT may degrade to passthrough).
   => (r/ok impl) | (r/err :error/no-translator-available {:requested :tried})."
  [routing config]
  (let [order (order-for (:translator routing) translator-priority)]
    (or (first-ok tl-reg/resolve-translator order config)
        (r/err :error/no-translator-available
               {:requested (:translator routing)
                :tried     order}))))
