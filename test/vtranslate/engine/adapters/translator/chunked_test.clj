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

;; an inner that never returns is bounded by :timeout-ms => the loud
;; chunk-timeout-error, never a hung batch (previously :timeout-ms was dead).
(deftest slow-chunk-times-out-into-loud-error
  (let [slow (reify p.tr/ITranslator
               (translate-batch [_ _ _ _ _] (Thread/sleep 3000) (r/ok ["nope"])))
        t    (chunked/make-chunked slow {:chunk-size 1 :timeout-ms 50})
        res  (p.tr/translate-batch t ["a" "b"] "en" "es" {})]
    (is (r/err? res))
    (is (= :error/translation-failed (:error res)))))

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

;; ---------------------------------------------------------------------------
;; Attempts: each one timed from its own start, cancelled when it overruns
;; ---------------------------------------------------------------------------

(defn- upper [texts] (r/ok (mapv clojure.string/upper-case texts)))

(defrecord HangsOnceOn [trigger calls interrupted]
  ;; Hangs the FIRST time it is handed a chunk holding `trigger`, delivering
  ;; true to `interrupted` when that hang is interrupted; answers every other call.
  p.tr/ITranslator
  (translate-batch [_ texts _sl _tl _opts]
    (when (and (some #{trigger} texts) (= 1 (swap! calls inc)))
      (try
        (Thread/sleep 10000)
        (catch InterruptedException _
          (deliver interrupted true))))
    (upper texts)))

(defrecord AlwaysHangs [calls]
  p.tr/ITranslator
  (translate-batch [_ texts _sl _tl _opts]
    (swap! calls inc)
    (Thread/sleep 10000)
    (upper texts)))

(defrecord ErrsOnce [calls]
  p.tr/ITranslator
  (translate-batch [_ texts _sl _tl _opts]
    (if (= 1 (swap! calls inc))
      (r/err :error/translation-failed {:reason "provider hiccup" :status 503})
      (upper texts))))

(defrecord TakesMs [ms]
  p.tr/ITranslator
  (translate-batch [_ texts _sl _tl _opts]
    (Thread/sleep (long ms))
    (upper texts)))

(deftest a-chunk-that-hangs-once-is-cancelled-and-retried
  (let [calls       (atom 0)
        interrupted (promise)
        t           (chunked/make-chunked (->HangsOnceOn "c" calls interrupted)
                                          {:chunk-size 2 :concurrency 2 :timeout-ms 200})
        res         (p.tr/translate-batch t ["a" "b" "c" "d" "e"] "en" "es" {})]
    (is (= (r/ok ["A" "B" "C" "D" "E"]) res) "every chunk, in order, none dropped")
    (is (= 2 @calls) "the hung chunk was attempted twice")
    (is (true? (deref interrupted 2000 false))
        "the hung attempt was interrupted, not left running")))

(deftest a-chunk-that-always-hangs-fails-loud-after-its-attempts
  (let [calls (atom 0)
        t     (chunked/make-chunked (->AlwaysHangs calls)
                                    {:chunk-size 2 :timeout-ms 100 :attempts 2})
        res   (p.tr/translate-batch t ["a" "b" "c"] "en" "es" {})]
    (is (r/err? res))
    (is (= :error/translation-failed (:error res)))
    (is (= 4 @calls) "two chunks, two attempts each")
    (is (= [:timed-out :timed-out] (mapv :outcome (:attempts res))))
    (is (= "chunk 1 of 2 (texts 0-1) not translated after 2 attempts: attempt 1 timed out after 100 ms; attempt 2 timed out after 100 ms"
           (:reason res))
        "the reason names the chunk, the attempts, and that they timed out")))

(deftest an-erring-chunk-is-retried-and-the-final-err-names-the-provider-error
  (testing "one err, then an answer: the chunk is retried and the batch succeeds"
    (let [t (chunked/make-chunked (->ErrsOnce (atom 0)) {:chunk-size 10})]
      (is (= (r/ok ["A" "B"]) (p.tr/translate-batch t ["a" "b"] "en" "es" {})))))
  (testing "errs on every attempt: the reason says it was the provider, and the provider's words survive"
    (let [res (p.tr/translate-batch (chunked/make-chunked (->AlwaysErr) {:chunk-size 10 :attempts 3})
                                    ["a"] "en" "es" {})]
      (is (= [:error :error :error] (mapv :outcome (:attempts res))))
      (is (clojure.string/includes? (:reason res) "after 3 attempts"))
      (is (clojure.string/includes? (:reason res) "failed (:error/translation-failed): boom")))))

(deftest each-attempt-deadline-starts-when-that-attempt-starts
  ;; One thread, four chunks each taking 60% of the bound, one attempt each: a
  ;; deadline counted from submit would cut the second chunk.
  (let [t (chunked/make-chunked (->TakesMs 180)
                                {:chunk-size 1 :concurrency 1 :timeout-ms 300 :attempts 1})]
    (is (= (r/ok ["A" "B" "C" "D"])
           (p.tr/translate-batch t ["a" "b" "c" "d"] "en" "es" {})))))

(deftest a-hung-chunk-costs-the-chunks-behind-it-nothing
  (let [done  (atom [])
        inner (reify p.tr/ITranslator
                (translate-batch [_ texts _ _ _]
                  (if (= ["a"] (vec texts))
                    (Thread/sleep 10000)
                    (Thread/sleep 180))
                  (swap! done conj (first texts))
                  (upper texts)))
        t     (chunked/make-chunked inner {:chunk-size 1 :concurrency 1
                                           :timeout-ms 300 :attempts 1})
        res   (p.tr/translate-batch t ["a" "b" "c"] "en" "es" {})]
    (is (r/err? res) "the hung chunk still fails the batch loud")
    (is (= 0 (:chunk-index res)))
    (is (= ["b" "c"] @done) "the chunks queued behind it each had their whole bound")))

(deftest a-timed-out-chunk-reaches-the-fallback
  (let [fb-calls (atom 0)
        t        (chunked/make-chunked (->AlwaysHangs (atom 0))
                                       {:chunk-size 5 :timeout-ms 100 :attempts 1
                                        :fallback (->RecordingUpper fb-calls)})]
    (is (= (r/ok ["A" "B"]) (p.tr/translate-batch t ["a" "b"] "en" "es" {})))
    (is (= 1 @fb-calls) "a timeout, not only an err, hands the chunk to the fallback")))

(deftest a-failing-fallback-is-named-in-the-reason
  (let [t   (chunked/make-chunked (->AlwaysHangs (atom 0))
                                  {:chunk-size 5 :timeout-ms 100 :attempts 1
                                   :fallback (->AlwaysErr)})
        res (p.tr/translate-batch t ["a"] "en" "es" {})]
    (is (= [:inner :fallback] (mapv :translator (:attempts res))))
    (is (clojure.string/includes? (:reason res) "fallback attempt 1 failed"))))

(deftest a-given-up-attempt-reports-nothing
  ;; The first attempt at chunk "a" ignores its interrupt and answers late. Its
  ;; answer is not the chunk's, so it must not be reported beside the retry's.
  (let [calls   (atom 0)
        inner   (reify p.tr/ITranslator
                  (translate-batch [_ texts _ _ _]
                    (when (and (= ["a"] (vec texts)) (= 1 (swap! calls inc)))
                      (let [until (+ (System/currentTimeMillis) 300)]
                        (while (< (System/currentTimeMillis) until))))
                    (upper texts)))
        reports (atom [])
        t       (:ok (chunked/wrap inner {:translator-opts {:chunk-size 1 :timeout-ms 100}}))
        res     (p.tr/translate-batch t ["a" "b"] "en" "es"
                                      {:on-chunk-translated #(swap! reports conj (:sources %))})]
    (Thread/sleep 400)
    (is (= (r/ok ["A" "B"]) res))
    (is (= 1 (count (filter #{["a"]} @reports))) "chunk a is reported once")))

(deftest wrap-reads-the-attempt-count
  (let [inner (->RecordingUpper (atom 0))]
    (is (= 3 (:attempts (:ok (chunked/wrap inner {:translator-opts {:chunk-size 2
                                                                    :chunk-attempts 3}})))))
    (is (= 2 (:attempts (:ok (chunked/wrap inner {:translator-opts {:chunk-size 2}}))))
        "two attempts when config names none")
    (is (= 2 (:attempts (:ok (chunked/wrap inner {:translator-opts {:chunk-size 2
                                                                    :chunk-attempts 0}}))))
        "a nonsensical count is not obeyed")))