(ns vtranslate.engine.adapters.translator.observed-test
  "The batch observer: every translated batch is reported with its texts,
   translations and transcript positions, under the chunker per chunk, and a
   report can never change or fail the batch."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hive-dsl.result :as r]
            [vtranslate.engine.port.translator :as p.tr]
            [vtranslate.engine.adapters.translator.chunked :as chunked]
            [vtranslate.engine.adapters.translator.observed :as observed]))

(def upper
  (reify p.tr/ITranslator
    (translate-batch [_ texts _ _ _] (r/ok (mapv str/upper-case texts)))))

(def failing
  (reify p.tr/ITranslator
    (translate-batch [_ _ _ _ _] (r/err :error/translation-failed {:reason "boom"}))))

(deftest a-batch-is-reported-with-what-it-was-given-and-what-came-back
  (let [seen (atom [])
        res  (p.tr/translate-batch (observed/wrap upper) ["do you?" "i do."] "en" "pt-BR"
                                   {:segment-indices [7 9]
                                    :on-chunk-translated #(swap! seen conj %)})]
    (is (= (r/ok ["DO YOU?" "I DO."]) res))
    (is (= [{:source-language "en" :target-language "pt-BR"
             :sources ["do you?" "i do."] :translations ["DO YOU?" "I DO."]
             :indices [7 9]}]
           @seen))))

(deftest under-the-chunker-each-chunk-names-its-own-positions
  (let [seen  (atom [])
        t     (chunked/make-chunked (observed/wrap upper) {:chunk-size 2 :concurrency 3})
        texts (mapv #(str "s" %) (range 5))
        res   (p.tr/translate-batch t texts "en" "es"
                                    {:segment-indices [10 11 12 13 14]
                                     :on-chunk-translated #(swap! seen conj %)})]
    (is (= (r/ok (mapv str/upper-case texts)) res))
    (is (= #{[10 11] [12 13] [14]} (set (map :indices @seen))))
    (is (every? #(= (map str/upper-case (:sources %)) (:translations %)) @seen))))

(deftest a-configured-chunker-reports-its-chunks-and-no-chunking-leaves-the-provider-bare
  (let [seen (atom [])
        t    (:ok (chunked/wrap upper {:translator-opts {:chunk-size 2}}))
        res  (p.tr/translate-batch t ["a" "b" "c"] "en" "es"
                                   {:segment-indices [0 1 2]
                                    :on-chunk-translated #(swap! seen conj %)})]
    (is (= (r/ok ["A" "B" "C"]) res))
    (is (= #{[0 1] [2]} (set (map :indices @seen)))
        "wiring needs no observer of its own: the chunker brings it"))
  (testing "without a chunk size the provider is returned as it was built"
    (is (identical? upper (:ok (chunked/wrap upper {:translator-opts {}}))))))

(deftest no-report-is-made-for-a-failed-batch-or-without-a-listener
  (let [seen (atom [])]
    (is (r/err? (p.tr/translate-batch (observed/wrap failing) ["a"] "en" "es"
                                      {:on-chunk-translated #(swap! seen conj %)})))
    (is (empty? @seen)))
  (is (= (r/ok ["A"]) (p.tr/translate-batch (observed/wrap upper) ["a"] "en" "es" {}))))

(deftest a-listener-that-throws-cannot-fail-the-batch
  (is (= (r/ok ["A"])
         (p.tr/translate-batch (observed/wrap upper) ["a"] "en" "es"
                               {:on-chunk-translated (fn [_] (throw (ex-info "view" {})))}))))

(deftest without-positions-the-report-says-so
  (let [seen (atom nil)]
    (p.tr/translate-batch (observed/wrap upper) ["a"] "en" "es"
                          {:on-chunk-translated #(reset! seen %)})
    (testing "nil, not invented positions"
      (is (nil? (:indices @seen))))))
