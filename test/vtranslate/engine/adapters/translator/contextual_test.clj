(ns vtranslate.engine.adapters.translator.contextual-test
  "Tests for the ContextualTranslator decorator: wrap gating, order/count
   preservation across context windows, per-window before/after cues, and
   fail-loud attribution when one window errs."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [hive-test.golden :refer [deftest-golden]]
            [hive-test.mutation :refer [deftest-mutations]]
            [hive-dsl.result :as r]
            [vtranslate.engine.port.translator :as p.tr]
            [vtranslate.engine.adapters.translator.contextual :as sut]))

;; --- stubs -----------------------------------------------------------------

(defn- recording-inner
  "Reify ITranslator that records each per-window call into `calls`
   (as {:texts :before :after}) and upper-cases the window's texts."
  [calls]
  (reify p.tr/ITranslator
    (translate-batch [_ texts _sl _tl opts]
      (swap! calls conj {:texts  (vec texts)
                         :before (:context/before opts)
                         :after  (:context/after opts)})
      (r/ok (mapv str/upper-case texts)))))

(defn- erring-inner
  "Reify ITranslator that errs (loud, distinctive) on the window whose :texts
   contain `bad`, else upper-cases. Records calls into `calls`."
  [calls bad err]
  (reify p.tr/ITranslator
    (translate-batch [_ texts _sl _tl opts]
      (swap! calls conj {:texts (vec texts)
                         :before (:context/before opts)
                         :after (:context/after opts)})
      (if (some #{bad} texts)
        err
        (r/ok (mapv str/upper-case texts))))))

(def ^:private inert (reify p.tr/ITranslator
                       (translate-batch [_ texts _ _ _] (r/ok (vec texts)))))

(defn- windows-by-first-text
  "Index recorded calls by their window's first text (unique per window),
   for concurrency-order-independent lookup."
  [calls]
  (into {} (map (juxt (comp first :texts) identity)) @calls))

;; =============================================================================
;; wrap — gating both ways
;; =============================================================================

(deftest wrap-gates-off-without-context-or-window
  (is (identical? inert (sut/wrap inert {})) "no :translator-opts => inner unchanged")
  (is (identical? inert (sut/wrap inert {:translator-opts {}})) "empty opts => inner")
  (is (identical? inert (sut/wrap inert {:translator-opts {:context 0}}))
      "context 0 is not positive => inner")
  (is (identical? inert (sut/wrap inert {:translator-opts {:context -3}}))
      "negative context => inner")
  (is (identical? inert (sut/wrap inert {:translator-opts {:context nil :window nil}}))
      "nil context + nil window => inner"))

(deftest wrap-gates-on-with-positive-context
  (let [w (sut/wrap inert {:translator-opts {:context 3}})]
    (is (not (identical? inert w)) "positive context => wrapped")
    (is (instance? vtranslate.engine.adapters.translator.contextual.ContextualTranslator w))
    (is (= 3 (:lookaround w)) "context feeds :lookaround")
    (is (= 40 (:window w)) "window defaults to 40")
    (is (identical? inert (:inner w)) "the original translator is the inner")))

(deftest wrap-gates-on-with-window
  (let [w (sut/wrap inert {:translator-opts {:window 10}})]
    (is (instance? vtranslate.engine.adapters.translator.contextual.ContextualTranslator w)
        "explicit window => wrapped even with no context")
    (is (= 10 (:window w)))
    (is (= 4 (:lookaround w)) "lookaround defaults to 4 when context absent")))

(deftest wrap-threads-window-context-and-bounds
  (let [w (sut/wrap inert {:translator-opts {:window 7 :context 2
                                             :concurrency 5 :timeout-ms 99}})]
    (is (= 7 (:window w)))
    (is (= 2 (:lookaround w)))
    (is (= 5 (:concurrency w)))
    (is (= 99 (:timeout-ms w)))))

;; =============================================================================
;; translate-batch — empty, order/count, and per-window context
;; =============================================================================

(deftest empty-batch-returns-ok-empty
  (let [t (sut/make-contextual (recording-inner (atom [])) {})]
    (is (= (r/ok []) (p.tr/translate-batch t [] "en" "es" {})))))

(deftest output-is-flat-translation-in-original-order
  (let [calls (atom [])
        t     (sut/make-contextual (recording-inner calls) {:window 3 :lookaround 2 :concurrency 4})
        input (mapv #(str "s" %) (range 10))
        res   (p.tr/translate-batch t input "en" "es" {})]
    (is (r/ok? res))
    (is (= (mapv str/upper-case input) (:ok res)) "concatenation preserves order")
    (is (= (count input) (count (:ok res))) "length preserved")
    (is (> (count @calls) 1) "inner invoked once per window => actually windowed")
    (is (= 4 (count @calls)) "10 texts / window 3 => 4 windows")))

(deftest each-window-carries-its-before-after-context
  (let [calls (atom [])
        t     (sut/make-contextual (recording-inner calls) {:window 3 :lookaround 2 :concurrency 4})
        input (mapv #(str "s" %) (range 10))
        _     (p.tr/translate-batch t input "en" "es" {})
        by    (windows-by-first-text calls)]
    (testing "first window: no before, next two as after"
      (is (= [] (:before (by "s0"))))
      (is (= ["s3" "s4"] (:after (by "s0")))))
    (testing "middle window: two-each lookaround on both sides"
      (is (= ["s1" "s2"] (:before (by "s3"))))
      (is (= ["s6" "s7"] (:after (by "s3")))))
    (testing "third window"
      (is (= ["s4" "s5"] (:before (by "s6"))))
      (is (= ["s9"] (:after (by "s6")))))
    (testing "last window: before clamps, no after"
      (is (= ["s7" "s8"] (:before (by "s9"))))
      (is (= [] (:after (by "s9")))))))

(deftest passthrough-opts-preserved-alongside-context
  (let [seen  (atom nil)
        inner (reify p.tr/ITranslator
                (translate-batch [_ texts _ _ opts]
                  (reset! seen opts)
                  (r/ok (vec texts))))
        t     (sut/make-contextual inner {:window 40 :lookaround 4})]
    (p.tr/translate-batch t ["a" "b"] "en" "es" {:extra/probe {"x" "y"} :segment-indices [0 1]})
    (is (= {"x" "y"} (:extra/probe @seen)) "caller opts flow through to inner")
    (is (contains? @seen :context/before) "context keys are added")
    (is (contains? @seen :context/after))))

;; =============================================================================
;; BACKLOG FOCUS — failure attribution: one window errs => whole batch errs LOUD
;; =============================================================================

(deftest one-window-err-fails-whole-batch-loud
  (let [calls (atom [])
        boom  (r/err :error/translation-failed {:reason "window-s5-boom"})
        t     (sut/make-contextual (erring-inner calls "s5" boom)
                                   {:window 3 :lookaround 2 :concurrency 4})
        input (mapv #(str "s" %) (range 10))
        res   (p.tr/translate-batch t input "en" "es" {})]
    (is (r/err? res) "a single failing window fails the batch")
    (is (= :error/translation-failed (:error res)) "surfaces the translator error keyword")
    (is (= "window-s5-boom" (:reason res))
        "surfaces THAT window's err verbatim — no dropped/reordered chunk, no timeout fallback")))

;; =============================================================================
;; GOLDEN — decorator wiring shape (flat output + per-window context), EDN-safe
;; =============================================================================

(defn- wiring-projection []
  (let [calls (atom [])
        t     (sut/make-contextual (recording-inner calls) {:window 3 :lookaround 1 :concurrency 4})
        input (mapv #(str "s" %) (range 7))
        res   (p.tr/translate-batch t input "en" "es" {})]
    {:output  (:ok res)
     :windows (->> @calls
                   (sort-by (comp first :texts))
                   (mapv #(select-keys % [:texts :before :after])))}))

(deftest-golden contextual-wiring-golden
  "test/golden/contextual-wiring.edn"
  (wiring-projection))

;; =============================================================================
;; MUTATION — break the load-bearing decorator wiring, prove assertions catch it
;; =============================================================================

(defn- context-check []
  (let [calls (atom [])
        t     (sut/make-contextual (recording-inner calls) {:window 3 :lookaround 2 :concurrency 4})
        input (mapv #(str "s" %) (range 10))
        res   (p.tr/translate-batch t input "en" "es" {})
        by    (windows-by-first-text calls)]
    (is (= (mapv str/upper-case input) (:ok res)))
    (is (= (count input) (count (:ok res))))
    (is (= [] (:before (by "s0"))))
    (is (= ["s3" "s4"] (:after (by "s0"))))
    (is (= ["s1" "s2"] (:before (by "s3"))))
    (is (= ["s6" "s7"] (:after (by "s3"))))
    (is (= ["s7" "s8"] (:before (by "s9"))))
    (is (= [] (:after (by "s9"))))))

(deftest-mutations translate-window-mutations-caught
  vtranslate.engine.adapters.translator.contextual/translate-window
  [["drop-context"
    (fn [inner sl tl opts {:keys [texts]}]
      (p.tr/translate-batch inner texts sl tl opts))]
   ["swap-before-after"
    (fn [inner sl tl opts {:keys [texts before after]}]
      (p.tr/translate-batch inner texts sl tl
                            (assoc opts :context/before after :context/after before)))]]
  context-check)

(deftest-mutations translate-windows-mutations-caught
  vtranslate.engine.adapters.translator.contextual/translate-windows
  [["reverse-window-order"
    (fn [{:keys [inner]} windows sl tl opts]
      (mapv (fn [{:keys [texts before after]}]
              (p.tr/translate-batch inner texts sl tl
                                    (assoc opts :context/before before :context/after after)))
            (reverse windows)))]
   ["drop-last-window"
    (fn [{:keys [inner]} windows sl tl opts]
      (mapv (fn [{:keys [texts before after]}]
              (p.tr/translate-batch inner texts sl tl
                                    (assoc opts :context/before before :context/after after)))
            (butlast windows)))]]
  context-check)

(deftest-mutations wrap-mutations-caught
  vtranslate.engine.adapters.translator.contextual/wrap
  [["never-wrap" (fn [inner _config] inner)]]
  (fn []
    (let [w (sut/wrap inert {:translator-opts {:context 3}})]
      (is (not (identical? inert w)))
      (is (instance? vtranslate.engine.adapters.translator.contextual.ContextualTranslator w)))))
