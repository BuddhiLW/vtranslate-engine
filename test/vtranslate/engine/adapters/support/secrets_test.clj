(ns vtranslate.engine.adapters.support.secrets-test
  "Shared API-key resolution: a pass: ref wins over env; env is the fallback."
  (:require [clojure.test :refer [deftest is]]
            [vtranslate.engine.adapters.support.secrets :as sut]))

(deftest resolve-key-nil-when-neither-source-present
  (is (nil? (sut/resolve-key "VT_DEFINITELY_UNSET_ENV_XYZ" nil))))

(deftest pass-ref-wins-over-env
  ;; a configured pass: path is authoritative — a stale env key must not shadow it
  (with-redefs [sut/cached-pass-show (fn [path] (when (= path "my/secret") "from-pass"))]
    (is (= "from-pass" (sut/resolve-key "SOME_ENV" "my/secret")))))

;; a successful pass lookup is cached per JVM (one subprocess per path)
(deftest pass-secret-is-memoized
  (let [calls (atom 0)]
    (reset! (deref #'sut/pass-cache) {})
    (with-redefs-fn {#'sut/pass-show (fn [_path] (swap! calls inc) "secret")}
      #(do
         (is (= "secret" (sut/resolve-key "MISSING_ENV" "pass/path")))
         (is (= "secret" (sut/resolve-key "MISSING_ENV" "pass/path")))
         (is (= 1 @calls))))))

(deftest env-used-when-no-pass-ref
  (with-redefs [sut/cached-pass-show (constantly nil)]
    (is (nil? (sut/resolve-key "VT_DEFINITELY_UNSET_ENV_XYZ" nil)))))