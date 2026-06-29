(ns vtranslate.engine.domain.shared-test
  "Property tests for the shared kernel — TimeRange ordering, Language registry,
   SourceRef locator validation."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [hive-test.properties :as props]
            [hive-dsl.result :as r]
            [vtranslate.engine.shared :as shared]))

(def gen-ms (gen/choose 0 100000))

(defn result? [x] (or (r/ok? x) (r/err? x)))

;; TOTAL: make-time-range never throws on any nat-int pair — always a Result.
(props/defprop-total make-time-range-total
  (fn [[a b]] (shared/make-time-range a b))
  (gen/tuple gen-ms gen-ms)
  {:pred result?})

;; PROPERTY: an ordered pair => ok, and duration = end - start.
(defspec time-range-ordered-ok 200
  (prop/for-all [a gen-ms b gen-ms]
    (let [[lo hi] (sort [a b])
          res     (shared/make-time-range lo hi)]
      (and (r/ok? res)
           (= (- hi lo) (shared/duration-ms (:ok res)))))))

;; PROPERTY: a strictly inverted pair => err.
(defspec time-range-inverted-err 200
  (prop/for-all [a gen-ms b gen-ms]
    (if (> a b)
      (r/err? (shared/make-time-range a b))
      true)))

;; SourceRef: a non-blank string => ok wrapping the uri; blank/non-string => err.
(deftest source-ref-validates-locator
  (is (= "/v.mp4" (:uri (:ok (shared/make-source-ref "/v.mp4")))))
  (is (r/ok? (shared/make-source-ref "https://x/y.mkv")))
  (is (r/err? (shared/make-source-ref "")))
  (is (r/err? (shared/make-source-ref "   ")))
  (is (r/err? (shared/make-source-ref nil))))

(defspec source-ref-nonblank-string-is-ok 200
  (prop/for-all [s (gen/such-that #(not (clojure.string/blank? %)) gen/string-alphanumeric 100)]
    (let [res (shared/make-source-ref s)]
      (and (r/ok? res) (= s (:uri (:ok res)))))))

;; PROPERTY: registry membership decides ok/err for make-language.
(defspec language-membership 200
  (prop/for-all [tag (gen/one-of [(gen/elements (vec shared/supported-languages))
                                  gen/string-alphanumeric])]
    (let [res (shared/make-language tag)]
      (if (contains? shared/supported-languages tag)
        (r/ok? res)
        (r/err? res)))))
