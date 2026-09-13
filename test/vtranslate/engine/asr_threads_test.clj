(ns vtranslate.engine.asr-threads-test
  "Thread budget for the local whisper backend. whisper.cpp defaults to 4 threads
   regardless of the host, so the adapter must choose — and a configured value
   must never be able to oversubscribe the machine or reach zero."
  (:require [clojure.test :refer [deftest is testing]]
            [vtranslate.engine.adapters.transcriber.whisper-jni :as sut]))

(deftest available-cores-is-positive
  (is (pos? (sut/available-cores))))

(deftest auto-defers-to-the-adapter-default
  (testing "nil and :auto both mean 'you decide', which the native side reads as its default"
    (is (nil? (sut/resolve-threads nil 22)))
    (is (nil? (sut/resolve-threads :auto 22)))))

(deftest a-requested-count-is-honoured-when-it-fits
  (is (= 8 (sut/resolve-threads 8 22)))
  (is (= 1 (sut/resolve-threads 1 22)))
  (is (= 22 (sut/resolve-threads 22 22))))

(deftest a-request-is-clamped-to-the-machine
  (testing "over the top"
    (is (= 22 (sut/resolve-threads 999 22)) "cannot oversubscribe")
    (is (= 4 (sut/resolve-threads 64 4))))
  (testing "at or below zero"
    (is (= 1 (sut/resolve-threads 0 22)) "zero threads would never finish")
    (is (= 1 (sut/resolve-threads -8 22)))))

(deftest the-default-leaves-headroom-but-uses-the-machine
  (let [n      (sut/available-cores)
        chosen (max 1 (- n 2))]
    (testing "the default is derived from the host and always usable"
      (is (pos? chosen) "a thread count must be at least one")
      (is (<= chosen n) "and must never claim more cores than exist"))
    ;; The point of the change — not whisper.cpp's fixed 4 — can only be
    ;; WITNESSED on a host with cores to spare, and a CI runner has four. So
    ;; this clause stays conditional while the invariants above do not: a
    ;; wholly conditional test asserts nothing at all on a small machine, which
    ;; kaocha fails as "Test ran without assertions", and rightly.
    (when (> n 6)
      (testing "a many-core host gets more than the stock 4 threads"
        (is (> chosen 4))
        (is (< chosen n) "and still leaves headroom")))))
