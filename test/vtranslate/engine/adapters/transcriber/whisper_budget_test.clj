(ns vtranslate.engine.adapters.transcriber.whisper-budget-test
  "The admission decision, on a machine with no GPU. Every number here is in
   MiB and converted at the edge, because that is how the measurements that
   produced this policy were written down."
  (:require [clojure.test :refer [deftest is testing]]
            [vtranslate.engine.adapters.transcriber.whisper-budget :as b]))

(defn- mib [n] (* (long n) 1024 1024))

(defn- state
  "A device holding `resident` (a map of key to MiB), with `lru` the eviction
   order and `pinned` the keys mid-decode."
  [budget-mib resident & {:keys [lru pinned]}]
  {:budget-bytes (mib budget-mib)
   :resident     (into {} (map (fn [[k v]] [k (mib v)])) resident)
   :pinned       (set pinned)
   :lru          (vec (or lru (keys resident)))})

(deftest a-model-that-fits-the-free-space-evicts-nothing
  (let [s (state 8192 {"large-v3" 1500} :lru ["large-v3"])
        p (b/plan s "turbo" (mib 2000))]
    (is (:admit? p))
    (is (= [] (:evict p)) "there was room, so nothing was given back")))

(deftest the-weights-already-loaded-are-simply-used
  (let [s (state 8192 {"large-v3" 1500})
        p (b/plan s "large-v3" (mib 1500))]
    (is (:admit? p))
    (is (:resident? p) "a cache hit is not an admission")
    (is (= [] (:evict p)))))

(deftest a-model-that-does-not-fit-evicts-least-recently-used-first
  ;; 8 GiB budget, three resident models leaving 1192 MiB free, and a 2000 MiB
  ;; newcomer. Evicting the oldest alone is enough.
  (let [s (state 8192 {"a" 2500 "b" 2500 "c" 2000} :lru ["b" "a" "c"])
        p (b/plan s "d" (mib 2000))]
    (is (:admit? p))
    (is (= ["b"] (:evict p)) "the least recently used went, and only it")))

(deftest eviction-stops-as-soon-as-the-newcomer-fits
  (let [s (state 8192 {"a" 3000 "b" 3000 "c" 1800} :lru ["a" "b" "c"])
        p (b/plan s "d" (mib 3000))]
    (is (:admit? p))
    (is (= ["a"] (:evict p))
        "freeing 3000 of the 392 MiB shortfall was enough; b and c stay")))

(deftest a-context-mid-decode-is-never-evicted
  (let [s (state 8192 {"a" 4000 "b" 4000} :lru ["a" "b"] :pinned ["a"])
        p (b/plan s "c" (mib 4000))]
    (is (:admit? p))
    (is (= ["b"] (:evict p)) "a was decoding, so b went instead")))

(deftest weights-are-refused-when-only-decoding-contexts-could-have-gone
  (let [s (state 8192 {"a" 4000 "b" 4000} :lru ["a" "b"] :pinned ["a" "b"])
        p (b/plan s "c" (mib 4000))]
    (is (not (:admit? p)) "refusing is the whole point: loading would kill the JVM")
    (is (= [] (:evict p)))
    (is (pos? (:shortfall p)))
    (is (= :budget/pinned-contexts-hold-the-device (:reason p)))))

(deftest weights-larger-than-the-device-are-named-as-such
  (let [s (state 4096 {} :lru [])
        p (b/plan s "huge" (mib 6000))]
    (is (not (:admit? p)))
    (is (= :budget/model-exceeds-device (:reason p))
        "an empty device that still cannot hold it is not an eviction problem")
    (is (= (mib 1904) (:shortfall p)))))

(deftest no-budget-means-the-old-never-evict-behaviour
  ;; A CPU context is bounded by host RAM, and a device we could not measure is
  ;; one we must not pretend to know.
  (let [s (assoc (state 0 {"a" 9000 "b" 9000}) :budget-bytes 0)
        p (b/plan s "c" (mib 9000))]
    (is (:admit? p))
    (is (= [] (:evict p)))))

(deftest cost-adds-the-compute-margin-to-the-weights
  (is (= (+ (mib 1100) b/compute-margin-bytes) (b/cost (mib 1100))))
  (testing "over-estimating refuses a row; under-estimating kills the JVM"
    (is (pos? b/compute-margin-bytes))))

(deftest touch-moves-a-key-to-the-most-recently-used-end
  (is (= ["b" "c" "a"] (b/touch ["a" "b" "c"] "a")))
  (is (= ["a" "b" "c"] (b/touch ["a" "b"] "c")) "a key not yet in the line joins it"))

(deftest forget-drops-what-was-freed-from-both-halves-of-the-state
  (let [s (b/forget (state 8192 {"a" 1 "b" 2 "c" 3} :lru ["a" "b" "c"]) ["a" "c"])]
    (is (= #{"b"} (set (keys (:resident s)))))
    (is (= ["b"] (:lru s)) "the line and the ledger cannot disagree")))

(deftest admit-records-the-cost-and-the-recency-together
  (let [s (b/admit (state 8192 {"a" 1000} :lru ["a"]) "b" (mib 2000))]
    (is (= (mib 2000) (get-in s [:resident "b"])))
    (is (= ["a" "b"] (:lru s)))))

(deftest a-refusal-explains-itself-in-the-units-the-operator-thinks-in
  (let [s (state 8192 {"a" 4000 "b" 4000} :lru ["a" "b"] :pinned ["a" "b"])
        p (b/plan s "c" (mib 4000))
        msg (b/explain s "c" (mib 4000) p)]
    (is (re-find #"4000 MiB needed" msg))
    (is (re-find #"8192 MiB budget" msg))
    (is (re-find #"8000 MiB resident" msg))
    (is (re-find #"mid-decode" msg))
    (is (re-find #"abort the JVM" msg)
        "the message has to say why refusing beats trying")))

(deftest a-plan-never-evicts-the-key-it-is-admitting
  ;; Degenerate but reachable: the same weights under a second cache key.
  (let [s (state 4096 {"a" 3900} :lru ["a"])
        p (b/plan s "a" (mib 3900))]
    (is (:resident? p))
    (is (= [] (:evict p)))))
