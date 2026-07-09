(ns vtranslate.engine.adapters.translator.augment-test
  "Generic opts-augmenting translator decorator: merges a fixed opts map into every
   translate-batch call, order/count-preserving, opaque to what the opts mean."
  (:require [clojure.test :refer [deftest is]]
            [hive-dsl.result :as r]
            [vtranslate.engine.port.translator :as p.tr]
            [vtranslate.engine.adapters.translator.augment :as sut]))

(defn- capturing [seen]
  (reify p.tr/ITranslator
    (translate-batch [_ texts _ _ opts]
      (reset! seen opts)
      (r/ok (mapv #(str % "!") texts)))))

(deftest wrap-opts-merges-extra-into-every-batch
  (let [seen (atom nil)
        t    (sut/wrap-opts (capturing seen) {:prompt/system-suffix "S" :extra 1})
        res  (p.tr/translate-batch t ["a" "b"] "en" "es" {:segment-indices [0 1]})]
    (is (= (r/ok ["a!" "b!"]) res) "inner Result + order/count pass through untouched")
    (is (= {:segment-indices [0 1] :prompt/system-suffix "S" :extra 1} @seen)
        "extra-opts merged over the caller opts")))

(deftest wrap-opts-caller-opts-win-nothing-clobbered-silently
  (let [seen (atom nil)
        t    (sut/wrap-opts (capturing seen) {:k "extra"})]
    (p.tr/translate-batch t ["x"] "en" "es" {:k "caller" :other 2})
    (is (= {:k "extra" :other 2} @seen) "extra-opts is authoritative on key collision")))

(deftest wrap-opts-empty-or-nil-returns-inner-unchanged
  (let [inner (capturing (atom nil))]
    (is (identical? inner (sut/wrap-opts inner nil)))
    (is (identical? inner (sut/wrap-opts inner {})))))
