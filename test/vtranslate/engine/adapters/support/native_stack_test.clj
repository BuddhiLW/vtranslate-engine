(ns vtranslate.engine.adapters.support.native-stack-test
  "The seam that keeps a deep native call off the default stack. The failure it
   exists for cannot be asserted from inside the JVM — a native stack overflow
   is a SIGSEGV, not a throwable — so what is tested here is everything AROUND
   it: the call really runs on a thread of its own with the stack we asked for,
   values and throwables cross back intact, and deep recursion that overflows a
   small stack survives a big one."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-dsl.result :as r]
            [vtranslate.engine.adapters.support.native-stack :as stack]))

(deftest the-value-comes-back
  (is (= 42 (stack/call-with-stack (fn [] 42))))
  (is (= :from-macro (stack/with-stack :from-macro))))

(deftest it-runs-somewhere-else
  (let [here (Thread/currentThread)
        there (stack/call-with-stack (fn [] (Thread/currentThread)))]
    (is (not= here there) "the point is a different stack, so a different thread")
    (is (= "vtranslate-native-stack" (.getName ^Thread there)))))

(deftest a-throwable-crosses-back-to-the-caller
  (testing "so r/guard and try/catch around the call keep working"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"boom"
                          (stack/call-with-stack (fn [] (throw (ex-info "boom" {}))))))
    (is (r/err? (r/guard Throwable (r/err :error/asr-failed {:reason "caught"})
                  (stack/call-with-stack (fn [] (throw (ex-info "boom" {})))))))))

(deftest result-with-stack-keeps-the-railway
  (is (= {:ok :fine} (select-keys (stack/result-with-stack :error/asr-failed (fn [] (r/ok :fine)))
                                  [:ok])))
  (let [res (stack/result-with-stack :error/segmentation-failed
                                     (fn [] (throw (ex-info "native blew up" {}))))]
    (is (r/err? res))
    (is (= :error/segmentation-failed (:error res)))))

(defn- deep
  "Recursion that is not tail-recursive, so it consumes real stack frames."
  [n]
  (if (zero? n) 0 (inc (deep (dec n)))))

(deftest a-bigger-stack-holds-a-deeper-call
  (let [depth 20000]
    (testing "a depth that overflows a small stack"
      (is (thrown? StackOverflowError
                   (stack/call-with-stack (* 128 1024) (fn [] (deep depth))))))
    (testing "fits in the default big one — the whole point of owning the thread"
      (is (= depth (stack/call-with-stack (fn [] (deep depth))))))))
