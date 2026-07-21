(ns vtranslate.engine.calc.batching-test
  "Trifecta — golden + property + mutation — for the pure batching helpers:
   context-windows (plain-EDN windowing), reassemble (Result fold), and the
   language-routing algebra promoted out of api (index-groups / group-payload /
   zip-indices / scatter). No IO."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [hive-test.golden :refer [deftest-golden]]
            [hive-test.mutation :refer [deftest-mutations]]
            [hive-dsl.result :as r]
            [vtranslate.engine.calc.batching :as sut]))

(defn- project-result
  "Record-free projection of a reassemble Result for golden snapshotting."
  [res]
  (if (r/ok? res)
    {:ok? true :value (:ok res)}
    {:ok? false :error res}))

;; =============================================================================
;; GOLDEN — representative windowings + representative folds (plain EDN)
;; =============================================================================

(deftest-golden context-windows-golden
  "test/golden/batching-context-windows.edn"
  {:size2-la1 (sut/context-windows ["a" "b" "c" "d" "e"] 2 1)
   :size3-la0 (sut/context-windows ["a" "b" "c" "d"] 3 0)
   :size-huge (sut/context-windows ["a" "b"] 10 5)
   :size1-la2 (sut/context-windows ["a" "b" "c"] 1 2)
   :empty     (sut/context-windows [] 2 1)
   :size-zero (sut/context-windows ["a" "b" "c"] 0 1)
   :neg-la    (sut/context-windows ["a" "b" "c"] 2 -3)})

(deftest-golden reassemble-golden
  "test/golden/batching-reassemble.edn"
  {:all-ok        (project-result
                   (sut/reassemble [(r/ok ["a"]) (r/ok ["b" "c"]) (r/ok [])]))
   :empty         (project-result (sut/reassemble []))
   :first-failure (project-result
                   (sut/reassemble [(r/ok ["a"])
                                    (r/err :error/boom {:pos 1})
                                    (r/err :error/late {:pos 2})]))})

;; =============================================================================
;; PROPERTY — context-windows: total, covers input exactly in order, chunk and
;; context sizes bounded/clamped.
;; =============================================================================

(def ^:private gen-cw-input
  (gen/tuple (gen/vector gen/string-alphanumeric 0 15)
             (gen/choose -3 6)
             (gen/choose -3 4)))

(defspec context-windows-covers-and-bounds 300
  (prop/for-all [[texts size la] gen-cw-input]
    (let [ws       (sut/context-windows texts size la)
          v        (vec texts)
          eff-size (max 1 size)
          pad      (max 0 la)]
      (and (vector? ws)
           (every? #(and (vector? (:texts %)) (vector? (:before %)) (vector? (:after %))) ws)
           (= v (vec (mapcat :texts ws)))
           (every? #(<= 1 (count (:texts %)) eff-size) ws)
           (every? #(<= (count (:before %)) pad) ws)
           (every? #(<= (count (:after %)) pad) ws)
           (= (count ws)
              (if (zero? (count v)) 0
                  (int (Math/ceil (/ (count v) (double eff-size))))))))))

(defspec context-windows-clamped-neighbours 200
  (prop/for-all [n    (gen/choose 0 30)
                 size (gen/choose 1 6)
                 la   (gen/choose 0 5)]
    (let [v  (mapv #(str "t" %) (range n))
          ws (sut/context-windows v size la)]
      (every? (fn [w]
                (let [ts (:texts w)]
                  (or (empty? ts)
                      (let [start (Integer/parseInt (subs (first ts) 1))
                            end   (+ start (count ts))]
                        (and (= (:before w) (subvec v (max 0 (- start la)) start))
                             (= (:after w)  (subvec v end (min n (+ end la)))))))))
              ws))))

(defspec context-windows-reassembles-to-input 200
  (prop/for-all [n    (gen/choose 0 30)
                 size (gen/choose 1 6)
                 la   (gen/choose 0 5)]
    (let [v   (mapv #(str "t" %) (range n))
          ws  (sut/context-windows v size la)
          res (sut/reassemble (map #(r/ok (:texts %)) ws))]
      (and (r/ok? res) (= v (:ok res))))))

;; =============================================================================
;; PROPERTY — reassemble: first failure wins, else ok concat in order.
;; =============================================================================

(def ^:private gen-ok-result
  (gen/fmap r/ok (gen/vector gen/string-alphanumeric 0 4)))

(def ^:private gen-err-result
  (gen/fmap (fn [c] (r/err (keyword "error" (str "e" c)) {:pos c})) (gen/choose 0 99)))

(def ^:private gen-window-result
  (gen/one-of [gen-ok-result gen-ok-result gen-ok-result gen-err-result]))

(defspec reassemble-characterization 300
  (prop/for-all [results (gen/vector gen-window-result 0 8)]
    (let [res       (sut/reassemble results)
          firstfail (first (remove r/ok? results))]
      (if firstfail
        (= res firstfail)
        (and (r/ok? res)
             (= (vec (mapcat :ok results)) (:ok res)))))))

;; =============================================================================
;; UNIT — boundary behaviours
;; =============================================================================

(deftest empty-input-yields-no-windows
  (is (= [] (sut/context-windows [] 3 2))))

(deftest size-and-lookaround-are-clamped
  (is (= [{:texts ["a"] :before [] :after []}
          {:texts ["b"] :before [] :after []}]
         (sut/context-windows ["a" "b"] 0 -5))))

(deftest reassemble-empty-is-ok-empty
  (let [res (sut/reassemble [])]
    (is (r/ok? res))
    (is (= [] (:ok res)))))

(deftest reassemble-first-failure-wins
  (let [res (sut/reassemble [(r/ok ["a"])
                             (r/err :error/first {:n 1})
                             (r/err :error/second {:n 2})])]
    (is (r/err? res))
    (is (= :error/first (:error res)))
    (is (= 1 (:n res)))))

;; =============================================================================
;; MUTATION — break the load-bearing rules; assertions must catch each.
;; =============================================================================

(deftest-mutations context-windows-mutations-caught
  vtranslate.engine.calc.batching/context-windows
  [["no-before"     (fn [texts _size la]
                      (let [v (vec texts) n (count v) pad (max 0 la)]
                        (mapv (fn [start]
                                (let [end (min n (+ start 2))]
                                  {:texts (subvec v start end)
                                   :before []
                                   :after  (subvec v end (min n (+ end pad)))}))
                              (range 0 n 2))))]
   ["no-after"      (fn [texts _size la]
                      (let [v (vec texts) n (count v) pad (max 0 la)]
                        (mapv (fn [start]
                                (let [end (min n (+ start 2))]
                                  {:texts (subvec v start end)
                                   :before (subvec v (max 0 (- start pad)) start)
                                   :after  []}))
                              (range 0 n 2))))]
   ["step-one"      (fn [texts _size la]
                      (let [v (vec texts) n (count v) pad (max 0 la)]
                        (mapv (fn [start]
                                (let [end (min n (+ start 2))]
                                  {:texts (subvec v start end)
                                   :before (subvec v (max 0 (- start pad)) start)
                                   :after  (subvec v end (min n (+ end pad)))}))
                              (range 0 n 1))))]
   ["swap-context"  (fn [texts _size la]
                      (let [v (vec texts) n (count v) pad (max 0 la)]
                        (mapv (fn [start]
                                (let [end (min n (+ start 2))]
                                  {:texts (subvec v start end)
                                   :before (subvec v end (min n (+ end pad)))
                                   :after  (subvec v (max 0 (- start pad)) start)}))
                              (range 0 n 2))))]]
  (fn []
    (is (= [{:texts ["a" "b"] :before []    :after ["c"]}
            {:texts ["c" "d"] :before ["b"] :after ["e"]}
            {:texts ["e"]     :before ["d"] :after []}]
           (sut/context-windows ["a" "b" "c" "d" "e"] 2 1)))))

(deftest-mutations reassemble-mutations-caught
  vtranslate.engine.calc.batching/reassemble
  [["ignore-failure" (fn [wr] (r/ok (into [] (mapcat :ok) wr)))]
   ["reverse-order"  (fn [wr] (r/ok (into [] (mapcat :ok) (reverse wr))))]
   ["first-only"     (fn [wr] (first wr))]
   ["last-failure"   (fn [wr] (or (last (remove r/ok? wr))
                                  (r/ok (into [] (mapcat :ok) wr))))]]
  (fn []
    (let [ok-res (sut/reassemble [(r/ok ["a"]) (r/ok ["b" "c"]) (r/ok [])])]
      (is (r/ok? ok-res))
      (is (= ["a" "b" "c"] (:ok ok-res))))
    (let [err-res (sut/reassemble [(r/ok ["a"])
                                   (r/err :error/boom {:pos 1})
                                   (r/err :error/late {:pos 2})])]
      (is (r/err? err-res))
      (is (= :error/boom (:error err-res)))
      (is (= 1 (:pos err-res))))))

;; =============================================================================
;; LANGUAGE-ROUTING ALGEBRA — index-groups / group-payload / zip-indices /
;; scatter. These are the pure batching/ordering slab promoted out of api: a
;; transcript's segments are indexed and grouped by source language, each group
;; is translated, and the results are scattered back into original segment order.
;; =============================================================================

(def ^:private gen-item
  (gen/fmap (fn [[t g]] {:text t :group g})
            (gen/tuple gen/string-alphanumeric (gen/elements ["de" "es" "en" "fr"]))))

(def ^:private gen-items (gen/vector gen-item 0 12))

(defn- err-fn
  "A domain-agnostic on-mismatch/on-incomplete for the algebra under test."
  [tag]
  (fn [expected actual] (r/err tag {:expected expected :actual actual})))

;; ---- PROPERTY — index-groups is a total, order-preserving indexed partition --

(defspec index-groups-is-a-total-indexed-partition 300
  (prop/for-all [items gen-items]
    (let [v      (vec items)
          groups (sut/index-groups items :group)
          all    (mapcat val groups)
          idxs   (map first all)]
      (and (map? groups)
           ;; every 0..n-1 index present exactly once (a permutation)
           (= (range (count v)) (sort idxs))
           ;; each pair sits under the key its item resolves to
           (every? (fn [[k pairs]]
                     (every? (fn [[_ item]] (= k (:group item))) pairs))
                   groups)
           ;; within a group indices strictly increase => original order kept
           (every? (fn [[_ pairs]] (apply < -1 (map first pairs))) groups)
           ;; the item recorded at each index is exactly the original
           (every? (fn [[i item]] (= (nth v i) item)) all)))))

;; ---- PROPERTY — scatter left-inverts the index-groups indexing --------------

(defspec scatter-inverts-index-groups 300
  (prop/for-all [items gen-items]
    (let [n      (count items)
          groups (sut/index-groups items :group)
          pairs  (mapcat (fn [[_ g]]
                           (let [{:keys [indices values]} (sut/group-payload g identity)]
                             (map vector indices values)))
                         groups)
          res    (sut/scatter n pairs (err-fn :error/gap))]
      (and (r/ok? res)
           (= (vec items) (:ok res))))))

;; ---- PROPERTY — scatter is ok iff every index is filled, else reports counts -

(defspec scatter-complete-iff-every-index-filled 300
  (prop/for-all [n       (gen/choose 1 12)
                 removed (gen/set (gen/choose 0 11))]
    (let [removed (into #{} (filter #(< % n) removed))
          full    (mapv (fn [i] [i (str "v" i)]) (range n))
          kept    (remove (fn [[i _]] (contains? removed i)) full)
          res     (sut/scatter n kept (err-fn :error/gap))]
      (if (empty? removed)
        (and (r/ok? res) (= (mapv second full) (:ok res)))
        (and (r/err? res)
             (= :error/gap (:error res))
             (= n (:expected res))
             (= (count kept) (:actual res)))))))

;; ---- PROPERTY — zip-indices aligns iff counts match, else reports counts -----

(defspec zip-indices-aligns-iff-counts-equal 300
  (prop/for-all [indices (gen/vector (gen/choose 0 50) 0 8)
                 values  (gen/vector gen/string-alphanumeric 0 8)]
    (let [res (sut/zip-indices indices values (err-fn :error/mismatch))]
      (if (= (count indices) (count values))
        (and (r/ok? res) (= (mapv vector indices values) (:ok res)))
        (and (r/err? res)
             (= :error/mismatch (:error res))
             (= (count indices) (:expected res))
             (= (count values) (:actual res)))))))

;; ---- PROPERTY — group-payload projects indices + values in group order ------

(defspec group-payload-projects-in-order 200
  (prop/for-all [pairs (gen/vector
                        (gen/tuple (gen/choose 0 99)
                                   (gen/fmap (fn [t] {:text t}) gen/string-alphanumeric))
                        0 8)]
    (let [{:keys [indices values]} (sut/group-payload pairs :text)]
      (and (= (mapv first pairs) indices)
           (= (mapv (comp :text second) pairs) values)))))

;; ---- UNIT — algebra edges ---------------------------------------------------

(deftest index-groups-empty-is-empty-map
  (is (= {} (sut/index-groups [] :group))))

(deftest index-groups-keeps-index-and-first-appearance-order
  (is (= {"a" [[0 {:g "a" :t 1}] [2 {:g "a" :t 3}]]
          "b" [[1 {:g "b" :t 2}]]}
         (sut/index-groups [{:g "a" :t 1} {:g "b" :t 2} {:g "a" :t 3}] :g))))

(deftest scatter-reorders-by-index-not-arrival
  (is (= ["a" "b" "c"]
         (:ok (sut/scatter 3 [[2 "c"] [0 "a"] [1 "b"]] (err-fn :error/gap))))))

(deftest scatter-duplicate-index-last-wins
  (is (= ["z" "b"]
         (:ok (sut/scatter 2 [[0 "a"] [1 "b"] [0 "z"]] (err-fn :error/gap))))))

(deftest zip-indices-empty-pair-is-ok-empty
  (is (= [] (:ok (sut/zip-indices [] [] (err-fn :error/mismatch))))))

;; ---- MUTATION — index-groups: drop the index, drop the key, collapse keys ----

(deftest-mutations index-groups-mutations-caught
  vtranslate.engine.calc.batching/index-groups
  [["drop-index"    (fn [items key-fn] (group-by key-fn items))]
   ["no-index-pair" (fn [items key-fn] (group-by (fn [[_ item]] (key-fn item)) (map vector items)))]
   ["collapse-keys" (fn [items _key-fn] (group-by (constantly :all) (map-indexed vector items)))]]
  (fn []
    (is (= {"a" [[0 {:g "a" :t 1}] [2 {:g "a" :t 3}]]
            "b" [[1 {:g "b" :t 2}]]}
           (sut/index-groups [{:g "a" :t 1} {:g "b" :t 2} {:g "a" :t 3}] :g)))))

;; ---- MUTATION — scatter: ignore gaps, drop index placement, sort values ------

(deftest-mutations scatter-mutations-caught
  vtranslate.engine.calc.batching/scatter
  [["ignore-gaps" (fn [n indexed _on]
                    (r/ok (reduce (fn [acc [i v]] (assoc acc i v)) (vec (repeat n nil)) indexed)))]
   ["drop-index"  (fn [_n indexed _on] (r/ok (mapv second indexed)))]
   ["sort-values" (fn [n indexed on]
                    (let [m ::m
                          d (reduce (fn [a [i v]] (assoc a i v)) (vec (repeat n m)) indexed)]
                      (if (some #{m} d)
                        (on n (count (remove #{m} d)))
                        (r/ok (vec (sort d))))))]]
  (fn []
    (is (= ["c" "b" "a"]
           (:ok (sut/scatter 3 [[2 "a"] [0 "c"] [1 "b"]] (err-fn :error/gap)))))
    (is (r/err? (sut/scatter 3 [[0 "a"] [2 "c"]] (err-fn :error/gap))))))
