(ns vtranslate.engine.calc.subprocess-env
  "Calc: which environment variables a media subprocess is allowed to see.

   ffmpeg, ffprobe and tesseract parse bytes an attacker influenced, and a
   ProcessBuilder hands its child the JVM's whole environment unless something
   takes it away. On a deployed worker that environment holds every provider
   credential the job never needed, so the default is to DENY and to name the
   few variables a media tool actually reads.

   Nothing here touches the real environment. `sanitize` maps one map to
   another, so the policy is a value a test asserts directly rather than a
   side effect it has to observe."
  (:require [clojure.string :as str]))

(def default-allow
  "Exact names a media tool legitimately reads. None of them is a credential.

   LD_LIBRARY_PATH earns its place: on a GPU node the driver libraries are
   injected that way, so dropping it would break NVENC rather than secure it.
   LD_PRELOAD is deliberately absent, being a code-injection channel and never
   something ffmpeg needs."
  #{"PATH"
    "HOME"
    "LANG"
    "TMPDIR"
    "TESSDATA_PREFIX"
    "LD_LIBRARY_PATH"})

(def default-allow-prefixes
  "Families passed through whole. LC_ covers the locale variables that decide
   how tesseract writes its TSV; XDG_ and FONTCONFIG_ are where fontconfig
   looks for the font cache libass reads; NVIDIA_ and CUDA_ are how a GPU node
   says which device this process may open."
  ["LC_" "XDG_" "FONTCONFIG_" "NVIDIA_" "CUDA_"])

(def default-overrides
  "Values forced regardless of what the parent had.

   HOME is pinned away from the worker's real home so a media parser cannot be
   steered at the user's dotfiles through it. Measured 2026-09-15 on a host
   with 2548 fonts: a libass burn takes 75 ms per run with either HOME, and the
   output is byte-identical, because the font cache that matters is the system
   one under /var/cache/fontconfig. The safer value is free."
  {"HOME" "/nonexistent"})

(def default-policy
  {:allow          default-allow
   :allow-prefixes default-allow-prefixes
   :overrides      default-overrides})

(defn allowed?
  "Whether `k` survives `policy`, by exact name or by family prefix."
  [{:keys [allow allow-prefixes]} k]
  (let [k (str k)]
    (boolean (or (contains? allow k)
                 (some #(str/starts-with? k %) allow-prefixes)))))

(defn sanitize
  "`env` (a name to value map, typically `(System/getenv)`) reduced to what
   `policy` admits, with the policy's overrides applied last so they win over
   an inherited value of the same name.

   `policy` merges over `default-policy`, so a caller adds one name without
   restating the rest. Entries with a nil name or value are dropped: a
   ProcessBuilder environment rejects them, and a nil here means the caller
   read a variable that was never set."
  ([env] (sanitize env nil))
  ([env policy]
   (let [policy (merge default-policy policy)
         pairs  (fn [m] (for [[k v] m :when (and (some? k) (some? v))]
                          [(str k) (str v)]))]
     (into (into {} (filter #(allowed? policy (first %))) (pairs env))
           (pairs (:overrides policy))))))

(defn withheld
  "The names `policy` strips from `env`, sorted. For a startup log line that
   says what the worker is keeping to itself, and for a test that wants to
   name a credential rather than assert an absence."
  ([env] (withheld env nil))
  ([env policy]
   (let [kept (set (keys (sanitize env policy)))]
     (->> (keys (into {} env))
          (map str)
          (remove kept)
          sort
          vec))))
