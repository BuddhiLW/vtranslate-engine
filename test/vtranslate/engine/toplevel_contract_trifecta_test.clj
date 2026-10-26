(ns vtranslate.engine.toplevel-contract-trifecta-test
  (:require [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.schema.toplevel-contract :as c]
            [vtranslate.engine.addons :as addons]
            [vtranslate.engine.residency :as residency]
            [vtranslate.engine.parallel :as parallel]
            [vtranslate.engine.version :as version]
            [vtranslate.engine.wiring :as wiring]
            [vtranslate.engine.providers.config :as config]))

(defn addon-observation [[id order]]
  (let [spec {:id id :ns 'fixture.addon :order order :config {:fixture true}}]
    {:in spec :out (addons/normalize-spec spec)}))
(deftrifecta addon-normalization addon-observation
  {:golden-path "test/golden/toplevel-contract-addon.edn"
   :cases {:plain [:fixture 0] :catalog [:vtranslate/context 50]}
   :gen (gen/tuple gen/keyword gen/int)
   :pred (fn [{:keys [in out]}]
           (and (m/validate c/Addon out)
                (= (select-keys in [:id :ns :order :config]) (select-keys out [:id :ns :order :config]))
                (= out (addons/normalize-spec out))))})

(defn addon-forms-observation [s]
  {:in s :out (mapv addons/normalize-spec [(symbol s) s (keyword s) nil])})
(deftrifecta addon-shorthands addon-forms-observation
  {:golden-path "test/golden/toplevel-contract-addon-forms.edn"
   :cases {:simple "fixture" :qualified "fixture.addon"}
   :gen (gen/fmap #(str "fixture" %) gen/string-alphanumeric)
   :pred (fn [{:keys [in out]}]
           (and (every? #(m/validate c/Addon %) out)
                (= (symbol in) (:ns (out 0)) (:ns (out 1)) (:ns (out 2)))
                (= {:ns nil :config {} :invalid nil} (out 3))))})

(defn alias-observation [xs]
  {:in xs :out (addons/classpath-aliases xs)})
(deftrifecta addon-alias-order alias-observation
  {:golden-path "test/golden/toplevel-contract-aliases.edn"
   :cases {:none [] :repeated [:vtranslate/context :vtranslate/fetch :vtranslate/context]
           :explicit [{:ns 'fixture :classpath/aliases [:z :a :z]}]}
   :gen (gen/vector (gen/elements [:vtranslate/context :vtranslate/fetch :unknown]))
   :pred (fn [{:keys [in out]}]
           (and (m/validate c/Aliases out)
                (= (vec (distinct (mapcat #(get (addons/normalize-spec %) :classpath/aliases) in))) out)
                (= (count out) (count (set out)))) )})

(defn cost-observation [[cost default known?]]
  (let [policy {:model-mib {"known" cost} :default-model-mib default} model (if known? "known" "other")]
    {:in {:cost cost :default default :known known?} :out (residency/cost-bytes policy model)}))
(deftrifecta residency-cost cost-observation
  {:golden-path "test/golden/toplevel-contract-cost.edn"
   :cases {:known [7 2 true] :fallback [7 2 false] :zero [0 0 true]}
   :gen (gen/tuple (gen/choose 0 100000) (gen/choose 0 100000) gen/boolean)
   :pred (fn [{:keys [in out]}]
           (and (m/validate c/Bytes out) (= (* 1048576 (if (:known in) (:cost in) (:default in))) out)))})

(defn reconcile-observation [loaded]
  (let [policy {:budget-mib 100 :model-mib {"a" 1 "b" 2 "c" 3} :default-model-mib 4}
        ledger {:resident {"a" 99 "b" 88} :lru ["b" "a"]}]
    {:in loaded :out (residency/reconcile policy ledger loaded)}))
(deftrifecta residency-reconcile reconcile-observation
  {:golden-path "test/golden/toplevel-contract-reconcile.edn"
   :cases {:none #{} :known #{"a" "b"} :strangers #{"c" "d" "a"}}
   :gen (gen/set (gen/elements ["a" "b" "c" "d"]))
   :pred (fn [{:keys [in out]}]
           (and (m/validate c/Ledger out)
                (= in (set (keys (:resident out))) (set (:lru out)))
                (= (count in) (count (:lru out)))
                (= (vec (concat (sort (remove #{"a" "b"} in)) (filter in ["b" "a"]))) (:lru out))
                (every? (fn [[id bytes]] (= (get {"a" 99 "b" 88 "c" (* 3 1048576) "d" (* 4 1048576)} id) bytes)) (:resident out))
                (= #{} (:pinned out)) (= (* 100 1048576) (:budget-bytes out))))})

(defn refusal-observation [[want short]]
  {:in [want short]
   :out (residency/refusal {:budget-bytes (* 10 1048576) :resident {"a" 3} :pinned #{"a"}}
                           "new" (* want 1048576) {:shortfall (* short 1048576) :reason :budget})})
(deftrifecta residency-refusal refusal-observation
  {:golden-path "test/golden/toplevel-contract-refusal.edn"
   :cases {:none [0 0] :short [12 2]}
   :gen (gen/tuple (gen/choose 0 10000) (gen/choose 0 10000))
   :pred (fn [{:keys [in out]}]
           (and (m/validate c/Refusal out) (= in [(:needed-mib out) (:short-mib out)])
                (= 10 (:budget-mib out)) (= ["a"] (:resident out) (:busy out))))})

(defn owner-observation [budget]
  (let [host (Object.) policy {:budget-mib budget}
        owner (residency/owner host policy)]
    {:in policy :out {:policy (:policy owner) :host (identical? host (:host owner))
                     :ledger @(:ledger owner)}}))

(deftrifecta residency-owner owner-observation
  {:golden-path "test/golden/toplevel-contract-owner.edn"
   :cases {:zero 0 :budget 4096} :gen (gen/choose 0 100000)
   :pred (fn [{:keys [in out]}]
           (and (m/validate [:map [:policy :map] [:host :boolean]
                                [:ledger [:map [:resident :map] [:lru [:vector :string]]]]] out)
                (= in (:policy out)) (:host out)
                (= {:resident {} :lru []} (:ledger out))))})

;; These are scheduling effects, but a pure workload supplies a deterministic
;; observable contract without timing assertions or sleeps.
(defn fanout-observation [[concurrency xs]]
  {:in xs :out (parallel/fan-out concurrency (fn [[i x]] [i (inc x)]) (map-indexed vector xs))})
(deftrifecta parallel-item-accounting fanout-observation
  {:golden-path "test/golden/toplevel-contract-fanout.edn"
   :cases {:empty [2 []] :duplicates [3 [1 1 2]] :clamped [0 [4 3]]}
   :gen (gen/tuple (gen/choose 0 4) (gen/vector (gen/choose -100 100) 0 16))
   :pred (fn [{:keys [in out]}]
           (and (m/validate [:vector c/Settled] out)
                (= (mapv (fn [i x] [i (inc x)]) (range) in) (mapv :value out))
                (= (count in) (count out))
                (= (set (range (count in))) (set (map (comp first :value) out)))))})

(defn each-observation [xs]
  {:in xs :out (parallel/run-each {:concurrency 3 :timeout-ms 10000} inc xs)})
(deftrifecta parallel-run-each each-observation
  {:golden-path "test/golden/toplevel-contract-run-each.edn"
   :cases {:empty [] :values [1 2 1]} :gen (gen/vector (gen/choose -100 100) 0 12)
   :pred (fn [{:keys [in out]}]
           (and (m/validate [:vector c/Settled] out) (= (mapv inc in) (mapv :value out))))})

(defn attempt-observation [x]
  {:in x :out (parallel/attempt 10000 (constantly x))})
(deftrifecta parallel-attempt attempt-observation
  {:golden-path "test/golden/toplevel-contract-attempt.edn"
   :cases {:zero 0 :negative -1} :gen gen/int
   :pred (fn [{:keys [in out]}] (and (m/validate c/Settled out) (= in (:value out))))})

(defn wiring-observation [area fail]
  (let [calls (atom [])
        build (fn [key cfg] (swap! calls conj key)
                (if (= key fail) {:error :error/pinned} {:ok key}))
        result (with-redefs [wiring/build-port build config/resolve-routing (fn [_] {:ok {}})]
                 ((case area :default wiring/default-ports :transcription wiring/transcription-ports
                        :parse wiring/parse-ports) {}))]
    {:in {:area area :fail fail} :out {:calls @calls :result result}}))
(defn wiring-valid? [{:keys [in out]}]
  (let [pairs (case (:area in)
                :default [[:media :media] [:segmenter :segmenter] [:transcriber :transcriber]
                          [:translator :translator] [:renderer :renderer] [:muxer :composer]
                          [:transcript-cache :transcript-cache]]
                :transcription [[:media :media] [:segmenter :segmenter] [:transcriber :transcriber]]
                :parse [[:source :source] [:parser :subtitle-parser] [:translator :translator] [:renderer :renderer]])
        order (mapv second pairs) hit (.indexOf order (:fail in))]
    (and (m/validate [:map [:calls [:vector :keyword]] [:result c/Result]] out)
         (= (if (neg? hit) order (subvec order 0 (inc hit))) (:calls out))
         (= (if (neg? hit) {:ok (into {} pairs)} {:error :error/pinned}) (:result out)))))
(def wiring-gen (gen/elements [nil :media :segmenter :transcriber :translator :renderer :composer :transcript-cache :source :subtitle-parser]))
(deftrifecta default-port-assembly (partial wiring-observation :default)
  {:golden-path "test/golden/toplevel-contract-default-ports.edn"
   :cases {:success nil :fail :translator} :gen wiring-gen :pred wiring-valid?})
(deftrifecta transcription-port-assembly (partial wiring-observation :transcription)
  {:golden-path "test/golden/toplevel-contract-transcription-ports.edn"
   :cases {:success nil :fail :transcriber} :gen wiring-gen :pred wiring-valid?})
(deftrifecta parse-port-assembly (partial wiring-observation :parse)
  {:golden-path "test/golden/toplevel-contract-parse-ports.edn"
   :cases {:success nil :fail :subtitle-parser} :gen wiring-gen :pred wiring-valid?})

(defn unwired-observation [k]
  {:in k :out (wiring/build-port k {})})
(deftrifecta unwired-port unwired-observation
  {:golden-path "test/golden/toplevel-contract-unwired.edn"
   :cases {:missing ::missing :another ::another}
   :gen (gen/fmap #(keyword "unwired-contract" %) gen/string-alphanumeric)
   :pred (fn [{:keys [in out]}]
           (and (m/validate c/Result out) (= :error/adapters-not-wired (:error out)) (= in (:port out))))})

(defn version-observation [x]
  {:in x :out {:version version/engine-version :identity x}})
(deftrifecta version-value version-observation
  {:golden-path "test/golden/toplevel-contract-version.edn"
   :cases {:zero 0 :one 1} :gen gen/int
   :pred (fn [{:keys [in out]}]
           (and (m/validate c/Version (:version out)) (= in (:identity out))
                (not (clojure.string/blank? (:version out)))))})
