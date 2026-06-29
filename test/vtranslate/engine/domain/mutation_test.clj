(ns vtranslate.engine.domain.mutation-test
  "Mutation witness (the M of the trifecta) — prove the language-registry checks
   actually CATCH broken implementations, not just pass on a correct one."
  (:require [clojure.test :refer [deftest is]]
            [hive-test.mutation :as mut]
            [hive-dsl.result :as r]
            [vtranslate.engine.shared :as shared]))

(mut/deftest-mutations make-language-mutations-caught
  vtranslate.engine.shared/make-language
  [["always-ok"  (fn [_tag] (r/ok "xx"))]
   ["always-err" (fn [tag]  (r/err :error/unsupported-language {:language tag}))]]
  (fn []
    (is (r/ok?  (shared/make-language "en")) "valid tag must be ok")
    (is (r/err? (shared/make-language "zz")) "unknown tag must be err")))
