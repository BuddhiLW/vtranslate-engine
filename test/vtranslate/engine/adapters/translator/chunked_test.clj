(ns vtranslate.engine.adapters.translator.chunked-test
  "Tests for the ChunkedTranslator decorator: order/count preservation across
   concurrent chunks, fail-loud on chunk err, and fallback retry."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-dsl.result :as r]
            [vtranslate.engine.port.translator :as p.tr]
            [vtranslate.engine.adapters.translator.chunked :as chunked]))

(defrecord RecordingUpper [calls]
  p.tr/ITranslator
  (translate-batch [_ texts _sl _tl _opts]
    (swap! calls inc)
    (r/ok (mapv clojure.string/upper-case texts))))

(defrecord AlwaysErr []
  p.tr/ITranslator
  (translate-batch [_ _texts _sl _tl _opts]
    (r/err :error/translation-failed {:reason "boom"})))

(deftest empty-batch-returns-ok-empty
  (let [t (chunked/make-chunked (->RecordingUpper (atom 0)) {})]
    (is (= (r/ok []) (p.tr/translate-batch t [] "en" "es" {})))))

(deftest chunks-preserve-order-and-count
  (let [calls (atom 0)
        inner (->RecordingUpper calls)
        t     (chunked/make-chunked inner {:chunk-size 3 :concurrency 2})
        input (mapv #(str "seg" %) (range 10))
        res   (p.tr/translate-batch t input "en" "es" {})]
    (is (r/ok? res))
    (is (= (mapv clojure.string/upper-case input) (:ok res))
        "output is upper-cased input in the SAME order")
    (is (= (count input) (count (:ok res))) "count preserved")
    (is (> @calls 1) "inner called more than once => actually chunked")
    (is (= 4 @calls) "10 texts / chunk-size 3 => 4 chunks")))

(deftest chunk-err-no-fallback-fails-whole-batch
  (let [t   (chunked/make-chunked (->AlwaysErr) {:chunk-size 3})
        res (p.tr/translate-batch t (mapv str (range 10)) "en" "es" {})]
    (is (r/err? res) "any chunk err => whole batch err (fail loud)")
    (is (= :error/translation-failed (:error res)))))

(deftest working-fallback-recovers-batch
  (let [fb-calls (atom 0)
        fallback (->RecordingUpper fb-calls)
        t        (chunked/make-chunked (->AlwaysErr)
                                       {:chunk-size 3 :fallback fallback})
        input    (mapv #(str "x" %) (range 10))
        res      (p.tr/translate-batch t input "en" "es" {})]
    (is (r/ok? res) "fallback rescues each failing chunk => ok")
    (is (= (mapv clojure.string/upper-case input) (:ok res))
        "fallback output preserves order + count")
    (is (pos? @fb-calls) "fallback translator was actually invoked")))

(deftest failing-fallback-still-fails-loud
  (testing "inner errs AND fallback errs => whole batch err"
    (let [t   (chunked/make-chunked (->AlwaysErr)
                                    {:chunk-size 3 :fallback (->AlwaysErr)})
          res (p.tr/translate-batch t (mapv str (range 5)) "en" "es" {})]
      (is (r/err? res))
      (is (= :error/translation-failed (:error res))))))
