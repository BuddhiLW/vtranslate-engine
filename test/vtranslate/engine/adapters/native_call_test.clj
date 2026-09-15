(ns vtranslate.engine.adapters.native-call-test
  "The INativeCall port and its two registered strategies.

   What is provable in-process is the SUBSTITUTION: both strategies satisfy the
   protocol, values and throwables cross back the same way through either, and
   the sized one really does run somewhere with the stack it was asked for. What
   is NOT provable here is the crash the port exists for, because a native stack
   overflow is a SIGSEGV and takes the test runner with it. That assertion lives
   in test-int/.../silero_vad_stack_int_test.clj, which forks a JVM so the death
   is something a test can observe instead of something it suffers."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-dsl.result :as r]
            [vtranslate.engine.adapters.native-call.caller-thread :as caller]
            [vtranslate.engine.adapters.native-call.sized-stack :as sized]
            [vtranslate.engine.calc.native-stack :as calc]
            [vtranslate.engine.port.native-call :as p]
            [vtranslate.engine.providers.native-call-registry :as reg]
            [hive-weave.stack :as stack]))

(def ^:private strategies
  {:caller-thread (caller/make-strategy nil)
   :sized-stack   (sized/make-strategy nil)})

;; --- the protocol holds for every strategy, which is what substitution means

(deftest every-strategy-returns-the-thunks-value
  (doseq [[k s] strategies]
    (testing (str k)
      (is (= 42 (p/call-native s (fn [] 42))))
      (is (= {:a 1} (p/call-native s (fn [] {:a 1})))))))

(deftest every-strategy-rethrows-to-the-caller
  (testing "so r/guard and try/catch around the call keep working either way"
    (doseq [[k s] strategies]
      (testing (str k)
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"boom"
                              (p/call-native s (fn [] (throw (ex-info "boom" {}))))))
        (is (r/err? (p/call-result s :error/asr-failed
                                   (fn [] (throw (ex-info "boom" {}))))))
        (is (= {:ok :fine}
               (select-keys (p/call-result s :error/asr-failed (fn [] (r/ok :fine)))
                            [:ok])))))))

(deftest every-strategy-describes-itself
  (is (= {:strategy :caller-thread} (p/describe (:caller-thread strategies))))
  (is (= {:strategy :sized-stack :stack-bytes calc/default-stack-bytes}
         (p/describe (:sized-stack strategies)))))

;; --- and they differ in exactly the way the port exists to express

(deftest the-strategies-differ-in-where-they-run
  (let [here (Thread/currentThread)]
    (testing "caller-thread is the identity: same stack, same thread"
      (is (identical? here (p/call-native (:caller-thread strategies)
                                          (fn [] (Thread/currentThread))))))
    (testing "sized-stack is the whole point: a different thread, so a different stack"
      (let [there (p/call-native (:sized-stack strategies)
                                 (fn [] (Thread/currentThread)))]
        (is (not= here there))
        (is (= sized/thread-name (.getName ^Thread there)))))))

(defn- deep
  "Recursion that is not tail-recursive, so it consumes real stack frames. A
   stand-in for the native recursion, which cannot be called from here without
   ending the test run."
  [n]
  (if (zero? n) 0 (inc (deep (dec n)))))

(deftest a-bigger-stack-holds-a-deeper-call
  (let [depth 20000]
    (testing "a depth that overflows a small stack"
      (is (thrown? StackOverflowError
                   (p/call-native (sized/->SizedStackCall (* 128 1024))
                                  (fn [] (deep depth))))))
    (testing "and fits in the default one"
      (is (= depth (p/call-native (:sized-stack strategies) (fn [] (deep depth))))))))

;; --- sizing policy

(deftest a-too-small-stack-is-raised-not-honoured
  (testing "from CONFIG, where small is a guess and guessing low is fatal"
    (is (= calc/minimum-stack-bytes (calc/stack-bytes {:stack-bytes 1024})))
    (is (= calc/minimum-stack-bytes
           (:stack-bytes (p/describe (sized/make-strategy {:stack-bytes 1024}))))))
  (testing "a sensible request is honoured"
    (is (= (* 32 1024 1024) (calc/stack-bytes {:stack-bytes (* 32 1024 1024)}))))
  (testing "absent, zero and nonsense fall back to the default"
    (is (= calc/default-stack-bytes (calc/stack-bytes nil)))
    (is (= calc/default-stack-bytes (calc/stack-bytes {})))
    (is (= calc/default-stack-bytes (calc/stack-bytes {:stack-bytes 0})))
    (is (= calc/default-stack-bytes (calc/stack-bytes {:stack-bytes "big"})))))

(deftest a-deliberate-small-stack-is-not-second-guessed
  (testing "the record constructor takes what it is given"
    (is (= (* 128 1024)
           (:stack-bytes (p/describe (sized/->SizedStackCall (* 128 1024)))))))
  (testing "and so does the facade's explicit arity, which is what lets a test
            demonstrate that the floor is load-bearing rather than decorative"
    (is (thrown? StackOverflowError
                 (stack/call-with-stack (* 128 1024) (fn [] (deep 20000)))))))

(deftest the-default-clears-what-was-measured-to-die
  (testing "a default at or below the failing size would be no fix at all"
    (is (> calc/default-stack-bytes (:dies-at calc/measured)))
    (is (>= calc/default-stack-bytes (:survives-at calc/measured)))
    (is (> calc/minimum-stack-bytes (:dies-at calc/measured)))))

;; --- registry (OCP)

(deftest the-registry-resolves-both-and-refuses-the-unknown
  (is (= {:strategy :caller-thread}
         (p/describe (:ok (reg/resolve-native-call :caller-thread {})))))
  (is (= {:strategy :sized-stack :stack-bytes (* 24 1024 1024)}
         (p/describe (:ok (reg/resolve-native-call
                           :sized-stack {:native-call {:stack-bytes (* 24 1024 1024)}})))))
  (testing "an unknown key names what is known rather than failing blank"
    (let [res (reg/resolve-native-call :teleport {})]
      (is (r/err? res))
      (is (= :error/unknown-native-call-strategy (:error res)))
      (is (contains? (set (:known res)) :sized-stack)))))

(deftest a-strategy-can-be-added-from-outside
  (testing "OCP: registering one edits no namespace in this repo"
    (defmethod reg/resolve-native-call ::counting [_ _]
      (r/ok (reify p/INativeCall
              (call-native [_ f] (f))
              (describe [_] {:strategy ::counting}))))
    (try
      (let [s (:ok (reg/resolve-native-call ::counting {}))]
        (is (= :ok (p/call-native s (fn [] :ok))))
        (is (= {:strategy ::counting} (p/describe s))))
      (finally
        (remove-method reg/resolve-native-call ::counting)))))
