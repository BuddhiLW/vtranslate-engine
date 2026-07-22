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
  [:whisper-local :whisper-ffm :sherpa-onnx :onnx-bytedeco
   :groq :openai-whisper :whisper-server])

(def translator-priority
  "Ordered MT fallback chain. Empty means no implicit passthrough."
  [])

(defn- first-ok
  "Try each provider key in order via `resolve-fn`. Return the first (r/ok ...);
   otherwise {:errors [[k <error-tag>] ...]} accumulating every candidate's failure
   IN ORDER — so a registered-but-misconfigured provider (its own build error) is
   distinguishable from an unknown one (:error/unknown-*), instead of both being
   masked as a bare 'no provider'."
  [resolve-fn keys config]
  (reduce (fn [acc k]
            (let [res (resolve-fn k config)]
              (if (r/ok? res)
                (reduced res)
                (update acc :errors conj [k (:error res)]))))
          {:errors []}
          keys))

(defn- order-for
  "Requested key first (when set), then the priority chain, de-duplicated."
  [requested priority]
  (vec (distinct (remove nil? (cons requested priority)))))

(defn resolve-active-transcriber
  "Resolve an ITranscriber: requested key first, then `transcriber-priority`.
   `routing` = {:transcriber kw|nil ...}; `config` carries adapter opts.
   => (r/ok impl) | (r/err :error/no-transcriber-available
                          {:requested :tried :errors [[k tag]...]}). The :errors
   accumulator distinguishes an unknown key from a registered-but-misconfigured one."
  [routing config]
  (let [order (order-for (:transcriber routing) transcriber-priority)
        res   (first-ok tr-reg/resolve-transcriber order config)]
    (if (r/ok? res)
      res
      (r/err :error/no-transcriber-available
             {:requested (:transcriber routing)
              :tried     order
              :errors    (:errors res)
              :hint      "configure an available ASR provider; ASR never falls back to a fake transcript"}))))

(defn resolve-active-translator
  "Resolve an ITranslator from the requested key plus configured fallback chain.
   => (r/ok impl) | (r/err :error/no-translator-available
                          {:requested :tried :errors [[k tag]...]})."
  [routing config]
  (let [order (order-for (:translator routing) translator-priority)
        res   (first-ok tl-reg/resolve-translator order config)]
    (if (r/ok? res)
      res
      (r/err :error/no-translator-available
             {:requested (:translator routing)
              :tried     order
              :errors    (:errors res)}))))
