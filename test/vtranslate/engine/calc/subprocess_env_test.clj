(ns vtranslate.engine.calc.subprocess-env-test
  "The policy is asserted as a function on maps, and then once for real: a
   child process started through the system runner is asked what it can see."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [vtranslate.engine.calc.subprocess-env :as sut]
            [vtranslate.engine.collect.process :as process]))

(def ^:private worker-env
  "A worker's environment, shaped like the deployed one: the variables a media
   tool reads, sitting next to every credential it must never see."
  {"PATH"               "/usr/bin:/bin"
   "HOME"               "/home/worker"
   "LANG"               "en_US.UTF-8"
   "LC_ALL"             "C.UTF-8"
   "TMPDIR"             "/tmp"
   "TESSDATA_PREFIX"    "/usr/share/tesseract-ocr/5/tessdata"
   "LD_LIBRARY_PATH"    "/usr/lib/x86_64-linux-gnu"
   "XDG_CACHE_HOME"     "/var/cache"
   "FONTCONFIG_PATH"    "/etc/fonts"
   "NVIDIA_DRIVER_CAPABILITIES" "compute,utility,video"
   "CUDA_VISIBLE_DEVICES" "0"
   ;; None of the following may reach a media parser.
   "OPENAI_API_KEY"     "sk-live-openai"
   "OPENROUTER_API_KEY" "sk-or-live"
   "VENICE_API_KEY"     "venice-live"
   "GROQ_API_KEY"       "gsk-live"
   "VT_STRIPE_SECRET"   "sk_live_stripe"
   "DATABASE_URL"       "postgres://user:pw@db/vtranslate"
   "AWS_SECRET_ACCESS_KEY" "aws-live"
   "LD_PRELOAD"         "/tmp/evil.so"})

(def ^:private credentials
  ["OPENAI_API_KEY" "OPENROUTER_API_KEY" "VENICE_API_KEY" "GROQ_API_KEY"
   "VT_STRIPE_SECRET" "DATABASE_URL" "AWS_SECRET_ACCESS_KEY"])

(deftest secrets-never-survive
  (let [out (sut/sanitize worker-env)]
    (testing "every credential is gone, by name"
      (doseq [k credentials]
        (is (not (contains? out k)) (str k " reached the child"))))
    (testing "and no secret VALUE survives under any other name"
      (let [values (set (vals out))]
        (doseq [k credentials]
          (is (not (contains? values (get worker-env k)))))))
    (testing "LD_PRELOAD is refused although it is not a credential"
      (is (not (contains? out "LD_PRELOAD"))))))

(deftest what-a-media-tool-needs-survives
  (let [out (sut/sanitize worker-env)]
    (testing "exact names"
      (doseq [k ["PATH" "LANG" "TMPDIR" "TESSDATA_PREFIX" "LD_LIBRARY_PATH"]]
        (is (= (get worker-env k) (get out k)) k)))
    (testing "families, by prefix"
      (doseq [k ["LC_ALL" "XDG_CACHE_HOME" "FONTCONFIG_PATH"
                 "NVIDIA_DRIVER_CAPABILITIES" "CUDA_VISIBLE_DEVICES"]]
        (is (= (get worker-env k) (get out k)) k)))))

(deftest home-is-overridden-not-inherited
  (testing "an inherited HOME loses to the override"
    (is (= "/nonexistent" (get (sut/sanitize worker-env) "HOME"))))
  (testing "and is set even when the parent had none"
    (is (= "/nonexistent" (get (sut/sanitize {"PATH" "/bin"}) "HOME")))))

(deftest policy-widens-without-restating-the-defaults
  (let [out (sut/sanitize worker-env {:allow (conj sut/default-allow "DATABASE_URL")})]
    (testing "the added name is admitted"
      (is (= "postgres://user:pw@db/vtranslate" (get out "DATABASE_URL"))))
    (testing "and the defaults still apply around it"
      (is (= "/usr/bin:/bin" (get out "PATH")))
      (is (not (contains? out "OPENAI_API_KEY")))
      (is (= "/nonexistent" (get out "HOME"))))))

(deftest a-child-environment-is-always-well-formed
  (testing "nil names and values are dropped, since ProcessBuilder rejects them"
    (let [out (sut/sanitize {"PATH" nil nil "/bin" "LANG" "C"})]
      (is (= #{"LANG" "HOME"} (set (keys out))))
      (is (every? string? (keys out)))
      (is (every? string? (vals out)))))
  (testing "the real environment sanitizes to strings only"
    (let [out (sut/sanitize (System/getenv))]
      (is (every? string? (keys out)))
      (is (every? string? (vals out))))))

(deftest withheld-names-what-was-taken-away
  (let [gone (sut/withheld worker-env)]
    (is (= (sort credentials) (sort (remove #{"LD_PRELOAD"} gone))))
    (is (not-any? (set gone) ["PATH" "LC_ALL" "CUDA_VISIBLE_DEVICES"]))
    (testing "an overridden name counts as kept, not withheld"
      (is (not (some #{"HOME"} gone))))))

;; ---------------------------------------------------------------------------
;; The policy reaching a real child. Everything above is a claim about a map;
;; this is the claim that ProcessBuilder honours it, which is the part that
;; would silently regress.
;; ---------------------------------------------------------------------------

(def ^:private env-bin
  (first (filter #(.canExecute (java.io.File. ^String %))
                 ["/usr/bin/env" "/bin/env"])))

(defn- child-env
  "The environment a child actually sees, run through `runner`."
  [runner]
  (let [{:keys [exit out]} (process/capture! runner [env-bin])]
    (when (zero? exit)
      (into {} (for [line (str/split-lines (str out))
                     :let [[k v] (str/split line #"=" 2)]
                     :when (seq k)]
                 [k v])))))

(deftest the-child-really-only-sees-the-allowlist
  (if-not env-bin
    (println "skipping: no env(1) on this host")
    (testing "a credential in the JVM's own environment does not reach the child"
      (let [seen (child-env process/system-runner)]
        (is (some? seen) "env(1) ran")
        (is (= "/nonexistent" (get seen "HOME")))
        (doseq [k (keys seen)]
          (is (sut/allowed? sut/default-policy k)
              (str k " reached the child but the policy does not admit it")))))))

(deftest an-explicit-runner-passes-exactly-what-it-was-given
  (if-not env-bin
    (println "skipping: no env(1) on this host")
    (let [seen (child-env (process/runner {"PATH" "/usr/bin:/bin" "VT_MARKER" "yes"}))]
      (is (= "yes" (get seen "VT_MARKER")))
      (is (not (contains? seen "HOME"))))))
