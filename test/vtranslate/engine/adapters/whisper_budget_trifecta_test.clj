(ns vtranslate.engine.adapters.whisper-budget-trifecta-test
  (:require [clojure.string :as str]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.adapters.transcriber.whisper-budget :as b]))

(def state-gen
  (gen/let [budget (gen/choose 1 1000) a (gen/choose 0 500)
            c (gen/choose 0 500) pinned gen/boolean]
    {:budget-bytes budget :resident {:a a :c c} :lru [:a :c]
     :pinned (if pinned #{:a} #{})}))
(defn cost-observation [n] {:in n :out (b/cost n)})
(deftrifecta cost cost-observation
  {:golden-path "test/golden/adapters-budget-cost.edn"
   :cases {:nil nil :zero 0 :weights 1024}
   :gen (gen/choose 0 10000000)
   :pred (fn [{:keys [in out]}]
           (and (m/validate :int out) (= (or in 0) (- out b/compute-margin-bytes))))})
(defn held-observation [resident] {:in resident :out (b/held resident)})
(deftrifecta held held-observation
  {:golden-path "test/golden/adapters-budget-held.edn"
   :cases {:empty {} :two {:a 20 :c 30}}
   :gen (gen/fmap :resident state-gen)
   :pred (fn [{:keys [in out]}]
           (and (m/validate [:int {:min 0}] out) (= out (reduce + 0 (vals in)))))})
(defn plan-observation [[state k n :as in]] {:in in :out (b/plan state k n)})
(deftrifecta plan plan-observation
  {:golden-path "test/golden/adapters-budget-plan.edn"
   :cases {:evict [{:budget-bytes 100 :resident {:a 80} :lru [:a] :pinned #{}} :b 50]
           :pinned [{:budget-bytes 100 :resident {:a 80} :lru [:a] :pinned #{:a}} :b 50]
           :resident [{:budget-bytes 1 :resident {:a 80}} :a 500]
           :unlimited [{:budget-bytes 0 :resident {}} :b 500]
           :oversize [{:budget-bytes 10 :resident {}} :b 11]}
   :gen (gen/tuple state-gen (gen/elements [:a :b]) (gen/choose 0 1200))
   :pred (fn [{[{:keys [budget-bytes resident pinned]} k n] :in out :out}]
           (and (m/validate [:map [:admit? :boolean] [:resident? :boolean]
                            [:evict [:vector :keyword]] [:shortfall [:int {:min 0}]]] out)
                (not-any? (set pinned) (:evict out))
                (not (some #{k} (:evict out)))
                (if (:resident? out) (contains? resident k)
                    (if (and (:admit? out) (pos? budget-bytes))
                      (<= (+ n (b/held (apply dissoc resident (:evict out)))) budget-bytes)
                      (or (:admit? out) (pos? (:shortfall out)))))))})
(defn touch-observation [[xs k :as in]] {:in in :out (b/touch xs k)})
(deftrifecta touch touch-observation
  {:golden-path "test/golden/adapters-budget-touch.edn"
   :cases {:empty [[] :a] :repeat [[:a :b :a] :a]}
   :gen (gen/tuple (gen/vector (gen/elements [:a :b :c]) 0 10) (gen/elements [:a :b :c]))
   :pred (fn [{[xs k] :in out :out}]
           (and (m/validate [:vector :keyword] out) (= k (peek out))
                (= (vec (remove #{k} xs)) (pop out))))})
(defn forget-observation [[s ks :as in]] {:in in :out (b/forget s ks)})
(deftrifecta forget forget-observation
  {:golden-path "test/golden/adapters-budget-forget.edn"
   :cases {:absent [{:resident {:a 2} :lru [:a]} [:b]]
           :all [{:resident {:a 2} :lru [:a]} [:a]]}
   :gen (gen/tuple state-gen (gen/vector (gen/elements [:a :b :c]) 0 3))
   :pred (fn [{[s ks] :in out :out}]
           (and (m/validate [:map [:resident [:map-of :keyword :int]] [:lru [:vector :keyword]]] out)
                (= (apply dissoc (:resident s) ks) (:resident out))
                (= (vec (remove (set ks) (:lru s))) (:lru out))
                (= (:pinned s) (:pinned out))))})
(defn admit-observation [[s k n :as in]] {:in in :out (b/admit s k n)})
(deftrifecta admit admit-observation
  {:golden-path "test/golden/adapters-budget-admit.edn"
   :cases {:new [{:resident {} :lru []} :b 2] :replace [{:resident {:a 2} :lru [:a]} :a 3]}
   :gen (gen/tuple state-gen (gen/elements [:a :b]) (gen/choose 0 1000))
   :pred (fn [{[s k n] :in out :out}]
           (and (m/validate [:map [:resident [:map-of :keyword :int]] [:lru [:vector :keyword]]] out)
                (= n (get-in out [:resident k])) (= k (peek (:lru out)))
                (= (dissoc (:resident s) k) (dissoc (:resident out) k))))})
(defn explain-observation [[s k n :as in]]
  {:in in :out (b/explain s k n (b/plan s k n))})
(deftrifecta explain explain-observation
  {:golden-path "test/golden/adapters-budget-explain.edn"
   :cases {:oversize [{:budget-bytes 1 :resident {}} :b 2]
           :pinned [{:budget-bytes 10 :resident {:a 10} :pinned #{:a} :lru [:a]} :b 2]}
   :gen (gen/tuple state-gen (gen/elements [:a :b]) (gen/choose 0 2000))
   :pred (fn [{[_ k] :in out :out}]
           (and (m/validate :string out) (str/includes? out (str k))
                (str/includes? out "abort the JVM")))})
