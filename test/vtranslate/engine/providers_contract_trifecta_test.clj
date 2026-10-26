(ns vtranslate.engine.providers-contract-trifecta-test
  (:require [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.schema.providers-contract :as c]
            [vtranslate.engine.providers.registry :as reg]
            [vtranslate.engine.providers.burner-registry :as b]
            [vtranslate.engine.providers.composer-registry :as co]
            [vtranslate.engine.providers.coverage-registry :as cv]
            [vtranslate.engine.providers.fetcher-registry :as f]
            [vtranslate.engine.providers.native-call-registry :as n]
            [vtranslate.engine.providers.segmenter-registry :as s]
            [vtranslate.engine.providers.transcriber-registry :as tr]
            [vtranslate.engine.providers.translator-registry :as tl]
            [vtranslate.engine.providers.router :as router]
            [vtranslate.engine.providers.compatibility :as compat]
            [vtranslate.engine.providers.decorators :as d]))

(defn registry-observation [multifn known config]
  (let [k ::registered missing ::missing
        old (get-method multifn k)
        present? (contains? (methods multifn) k)]
    (try
      (.addMethod ^clojure.lang.MultiFn multifn k (fn [key cfg] {:ok {:key key :config cfg}}))
      (let [unknown (multifn missing config)
            keys (vec (known))]
        {:in config
         :out [{:resolved (multifn k config) :unknown (select-keys unknown [:error])
                :known? (boolean (some #{k} keys)) :default? (boolean (some #{:default} keys))
                :diagnostic? (= (set keys) (set (get unknown :known)))}]})
      (finally (if present? (.addMethod ^clojure.lang.MultiFn multifn k old)
                   (remove-method multifn k))))))

;; Each registry gets its own trifecta and golden. Only a private, test-owned
;; dispatch key is installed; no real adapter factory (or native code) runs.
(defmacro registry-contract [label multifn known]
  `(deftrifecta ~label (partial registry-observation ~multifn ~known)
     {:golden-path ~(str "test/golden/providers-contract-" (name label) ".edn")
      :cases {:empty {} :options {:threads 2}}
      :gen (gen/map gen/keyword (gen/choose 0 20))
      :pred (fn [observation#]
              (and (m/validate c/RegistryObservation observation#)
                   (every? (fn [row#]
                             (and (= {:key ::registered :config (:in observation#)}
                                     (get-in row# [:resolved :ok]))
                                  (:known? row#) (not (:default? row#))
                                  (:diagnostic? row#))) (:out observation#))))}))

(registry-contract burner b/resolve-burner b/known)
(registry-contract composer co/resolve-composer co/known)
(registry-contract coverage cv/resolve-coverage-policy #(reg/known-keys cv/resolve-coverage-policy))
(registry-contract fetcher f/resolve-fetcher f/known)
(registry-contract native n/resolve-native-call #(reg/known-keys n/resolve-native-call))
(registry-contract segmenter s/resolve-segmenter s/known)
(registry-contract transcriber tr/resolve-transcriber tr/known)
(registry-contract translator tl/resolve-translator tl/known)

(defn keys-observation [xs]
  (let [mf (clojure.lang.MultiFn. "fixture" identity :default #'clojure.core/global-hierarchy)]
    (.addMethod mf :default identity)
    (doseq [x xs] (.addMethod mf x identity))
    {:in (set xs) :out (reg/known-keys mf)}))
(deftrifecta known-keys keys-observation
  {:golden-path "test/golden/providers-contract-known-keys.edn"
   :cases {:empty [] :duplicate [:a :a :b]} :gen (gen/vector (gen/elements [:a :b :c]))
   :pred (fn [{:keys [in out]}]
           (and (m/validate c/Keys out) (= in (set out)) (= (count in) (count out))))})

(defn unknown-observation [k]
  (let [mf (clojure.lang.MultiFn. "fixture" identity :default #'clojure.core/global-hierarchy)]
    (.addMethod mf :default identity)
    {:in k :out (reg/unknown-error :fixture k mf "hint")}))
(deftrifecta unknown-error unknown-observation
  {:golden-path "test/golden/providers-contract-unknown.edn"
   :cases {:a :a :b :b} :gen gen/keyword
   :pred (fn [{:keys [in out]}]
           (and (m/validate c/Unknown out) (= :error/unknown-fixture (:error out))
                (= in (get out :provider-key))
                (= [] (get out :known))))})

(defn router-observation [which [requested success]]
  (let [calls (atom [])
        resolve (fn [k cfg] (swap! calls conj k)
                  (if (= k success) {:ok [k cfg]} {:error :error/unavailable}))
        priority [:a :b :a]
        result (with-redefs [router/transcriber-priority priority router/translator-priority priority
                            tr/resolve-transcriber resolve tl/resolve-translator resolve]
                 ((if (= which :transcriber) router/resolve-active-transcriber router/resolve-active-translator)
                  {which requested} {:fixture true}))]
    {:in {:which which :requested requested :success success}
     :out {:result result :tried @calls}}))
(defn router-valid? [{:keys [in out]}]
  (let [order (vec (distinct (remove nil? (cons (:requested in) [:a :b :a]))))
        hit (.indexOf order (:success in))]
    (and (m/validate [:map [:result [:or [:map [:ok [:tuple :keyword :map]]] c/Unknown]] [:tried c/Keys]] out)
         (= (if (neg? hit) order (subvec order 0 (inc hit))) (:tried out))
         (if (neg? hit)
           (and (= (keyword "error" (str "no-" (name (:which in)) "-available")) (get-in out [:result :error]))
                (= order (get-in out [:result :tried])))
           (= [(:success in) {:fixture true}] (get-in out [:result :ok]))))))
(def router-gen (gen/tuple (gen/elements [nil :a :b :c]) (gen/elements [:a :b :c :missing])))
(deftrifecta active-transcriber (partial router-observation :transcriber)
  {:golden-path "test/golden/providers-contract-active-transcriber.edn"
   :cases {:fallback [nil :b] :requested [:c :c] :exhaust [:a :missing]}
   :gen router-gen :pred router-valid?})
(deftrifecta active-translator (partial router-observation :translator)
  {:golden-path "test/golden/providers-contract-active-translator.edn"
   :cases {:fallback [nil :b] :requested [:c :c] :exhaust [:a :missing]}
   :gen router-gen :pred router-valid?})

(defn compatibility-observation [[required produced]]
  (with-redefs [compat/segmentation-required (constantly required)
                compat/segmentation-produced (constantly produced)]
    {:in [required produced]
     :out {:incompatible (compat/incompatible? :stub :stub)
           :check (compat/check {:transcriber :stub :segmenter :stub})}}))
(deftrifecta pairing compatibility-observation
  {:golden-path "test/golden/providers-contract-pairing.edn"
   :cases {:bad [:utterance :fixed-window] :unknown [:utterance :unknown] :any [:any :fixed-window]}
   :gen (gen/tuple (gen/elements [:any :utterance]) (gen/elements [:utterance :fixed-window :unknown]))
   :pred (fn [{:keys [in out]}]
           (let [bad (= in [:utterance :fixed-window])]
             (and (m/validate [:map [:incompatible :boolean] [:check c/Compatibility]] out)
                  (= bad (:incompatible out))
                  (= bad (= :error/incompatible-routing (get-in out [:check :error])))
                  (or bad (= {:transcriber :stub :segmenter :stub} (get-in out [:check :ok]))))))})

(defn declaration-observation [k]
  {:in k :out [(compat/segmentation-required k) (compat/segmentation-produced k)]})
(deftrifecta undeclared-segmentation declaration-observation
  {:golden-path "test/golden/providers-contract-declarations.edn"
   :cases {:unknown ::unknown :another ::another}
   :gen (gen/fmap #(keyword "contract-missing" %) gen/string-alphanumeric)
   :pred (fn [{:keys [out]}]
           (and (m/validate [:tuple :keyword :keyword] out) (= [:any :unknown] out)))})

(defn config-path-observation [suffix]
  (let [override (str "/contract/" suffix "/config.edn")]
    {:in override :out ((requiring-resolve 'vtranslate.engine.providers.config/config-path) override)}))

(deftrifecta explicit-config-path config-path-observation
  {:golden-path "test/golden/providers-contract-config-path.edn"
   :cases {:plain "fixture" :empty-suffix ""}
   :gen gen/string-alphanumeric
   :pred (fn [{:keys [in out]}]
           (and (m/validate :string out) (= in out)))})

(defn decorators-observation [orders]
  (let [port ::isolated ids (mapv #(keyword (str "d" %)) (range (count orders)))
        expected (mapv first (sort-by (fn [[id order]] [order (str id)]) (map vector ids orders)))]
    (try
      (doseq [[id order] (map vector ids orders)]
        (d/register! port id {:order order :wrap (fn [p _] (conj p id))
                              :identity (fn [_] (name id))}))
      {:in {:orders orders :expected expected}
       :out {:contributions (mapv (fn [[id entry]] [id (select-keys entry [:order])]) (d/contributions port))
             :decorated (d/decorate port [] {}) :identities (d/identities port {})}}
      (finally (doseq [id ids] (d/unregister! port id))))))
(deftrifecta decorator-order-and-identities decorators-observation
  {:golden-path "test/golden/providers-contract-decorators.edn"
   :cases {:empty [] :ties [1 1 0] :reverse [3 2 1]}
   :gen (gen/vector (gen/choose -3 3) 0 8)
   :pred (fn [{:keys [in out]}]
           (and (m/validate [:map [:contributions c/Contributions] [:decorated c/Decorated] [:identities c/Identities]] out)
                (= (:expected in) (mapv first (:contributions out)) (get-in out [:decorated :ok]))
                (= (vec (sort (map #(str % "=" (name %)) (:expected in)))) (:identities out))))})
