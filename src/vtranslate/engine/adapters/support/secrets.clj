(ns vtranslate.engine.adapters.support.secrets
  "Shared API-key resolution for the OpenAI-compatible adapters (ASR + LLM). A
   configured `pass:` path (a pass-store secret) WINS over the env var — a stale
   env key must not shadow the real one — and successful pass lookups are cached
   per JVM. No network; the only effect is a `pass show` subprocess."
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]))

(defn pass-show
  "First line of `pass show <path>`, or nil (missing pass / non-zero exit)."
  [path]
  (try
    (let [{:keys [exit out]} (shell/sh "pass" "show" path)]
      (when (zero? exit) (some-> out str/split-lines first str/trim not-empty)))
    (catch Exception _ nil)))

(defonce ^:private pass-cache (atom {}))

(defn cached-pass-show
  "pass-show with a per-JVM cache keyed by `path` (nil path => nil, uncached)."
  [path]
  (when path
    (if-let [cached (get @pass-cache path)]
      cached
      (when-let [secret (pass-show path)]
        (swap! pass-cache assoc path secret)
        secret))))

(defn resolve-key
  "A configured `pass:` path wins over the env var (a stale env key must not shadow
   the real one). Successful pass lookups are cached per JVM. => key-string | nil."
  [secret-env secret-pass]
  (or (cached-pass-show secret-pass)
      (some-> (System/getenv secret-env) str/trim not-empty)))
