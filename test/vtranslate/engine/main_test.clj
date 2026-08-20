(ns vtranslate.engine.main-test
  "Boundary entrypoint — EDN spec parsing (argv vs stdin) and the top-level
   Throwable guard that funnels every escape into a structured Result."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [hive-test.properties :as props]
            [hive-dsl.result :as r]
            [vtranslate.engine.main :as main]))

(defn- result? [x] (or (r/ok? x) (r/err? x)))

;; read-spec: argv[0] wins.
(deftest read-spec-from-argv
  (is (= {:job-id "j" :source "/v.mp4"}
         (#'main/read-spec ["{:job-id \"j\" :source \"/v.mp4\"}"]))))

;; read-spec: no args => read the spec off stdin.
(deftest read-spec-from-stdin
  (is (= {:job-id "s"}
         (with-in-str "{:job-id \"s\"}" (#'main/read-spec [])))))

;; Malformed EDN can't crash the subprocess — the Throwable guard funnels the
;; parse failure into (r/err :error/uncaught {:phase :run}).
(deftest run-funnels-malformed-edn
  (let [res (main/run ["{:unbalanced "])]
    (is (r/err? res))
    (is (= :error/uncaught (:error res)))
    (is (= :run (:phase res)))))

;; A well-formed spec with no adapters wired returns the wiring err as a Result
;; (boundary never throws), and short-circuits at the first unbuilt port.
(deftest run-surfaces-provider-errors
  (main/register-adapters!)
  (let [res (main/run ["{:job-id \"j\" :source \"/v.mp4\" :target-language \"en\"}"])]
    (is (r/err? res))
    (is (contains? #{:error/adapters-not-wired
                     :error/no-translator-available
                     :error/transcriber-unavailable
                     ;; With :ffmpeg present the media boundary is genuinely
                     ;; wired, so the nonexistent fixture is reached first.
                     :error/source-unreadable}
                   (:error res)))))

;; register-adapters! is best-effort: a missing optional (:ffmpeg) dep is
;; swallowed so the core stays runnable; returns nil, never throws.
(deftest register-adapters-is-best-effort
  ;; a missing optional (:ffmpeg) dep is tolerated so the core stays runnable;
  ;; returns {:failed [...]} (never throws).
  (let [res (main/register-adapters!)]
    (is (map? res))
    (is (vector? (:failed res)))))

;; a core adapter that cannot be required is RECORDED, not silently swallowed.
(deftest register-adapters-records-load-failures
  (with-redefs [main/core-adapter-nses '[vtranslate.engine.--does-not-exist--]]
    (let [res (main/register-adapters! {})]
      (is (= 1 (count (:failed res))))
      (is (= 'vtranslate.engine.--does-not-exist-- (ffirst (:failed res)))))))

;; the boundary smart-ctor rejects an anemic spec before wiring any port, so a
;; nil :job-id can't leak into '-asset'/'-sub' ids downstream.
(deftest run-rejects-spec-missing-job-id
  (let [res (main/run ["{:source \"/v.mp4\" :target-language \"en\"}"])]
    (is (r/err? res))
    (is (= :error/invalid-job-spec (:error res)))
    (is (= [:job-id] (:missing res)))))

;; the ASR-only ingress carries no translation target, so the boundary must not
;; demand one — only :job-id and :source.
(deftest run-accepts-transcribe-spec-without-target-language
  (main/register-adapters!)
  (let [res (main/run ["{:operation :transcribe :job-id \"j\" :source \"/v.mp4\"}"])]
    (is (r/err? res))
    (is (not= :error/invalid-job-spec (:error res)))))

(deftest run-rejects-transcribe-spec-missing-source
  (let [res (main/run ["{:operation :transcribe :job-id \"j\"}"])]
    (is (r/err? res))
    (is (= :error/invalid-job-spec (:error res)))
    (is (= [:source] (:missing res)))))

;; TOTAL: run never throws — for ANY argv it returns a Result (the process-level
;; Throwable guard is the safety net the subprocess transport depends on).
(props/defprop-total run-is-total
  (fn [args] (main/run args))
  (gen/vector gen/string-ascii 0 3)
  {:pred result?})
