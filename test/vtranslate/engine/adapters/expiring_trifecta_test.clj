(ns vtranslate.engine.adapters.expiring-trifecta-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.schema.port-values :as p]
            [vtranslate.engine.adapters.transcript-cache.expiring :as e]
            [vtranslate.engine.port.transcript-cache :as cache]))
(def transcript {:segments [] :source-language "en"})
(defn envelope-observation [[t at :as in]] {:in in :out (e/envelope t at)})
(deftrifecta envelope envelope-observation
  {:golden-path "test/golden/adapters-expiring-envelope.edn"
   :cases {:epoch [transcript 0] :later [transcript 100]}
   :gen (gen/tuple (gen/return transcript) (gen/choose 0 100000))
   :pred (fn [{[t at] :in out :out}]
           (and (m/validate [:map [:transcript-cache/cached-at :int]
                            [:transcript-cache/transcript p/Transcript]] out)
                (= t (e/unwrap out)) (= at (get out e/cached-at-key))))})
(defn envelope?-observation [v] {:in v :out (e/envelope? v)})
(deftrifecta envelope? envelope?-observation
  {:golden-path "test/golden/adapters-expiring-detect.edn"
   :cases {:bare transcript :wrapped (e/envelope transcript 0) :nil nil}
   :gen (gen/one-of [(gen/return transcript) (gen/fmap #(e/envelope transcript %) gen/nat)])
   :pred (fn [{:keys [in out]}]
           (and (m/validate :boolean out) (= out (and (map? in) (contains? in e/cached-at-key)))))})
(defn unwrap-observation [v] {:in v :out (e/unwrap v)})
(deftrifecta unwrap unwrap-observation
  {:golden-path "test/golden/adapters-expiring-unwrap.edn"
   :cases {:bare transcript :wrapped (e/envelope transcript 3)}
   :gen (gen/one-of [(gen/return transcript) (gen/fmap #(e/envelope transcript %) gen/nat)])
   :pred (fn [{:keys [out]}] (and (m/validate p/Transcript out) (= transcript out)))})
(defn expired-observation [[at ttl now :as in]] {:in in :out (e/expired? (e/envelope transcript at) ttl now)})
(deftrifecta expired expired-observation
  {:golden-path "test/golden/adapters-expiring-age.edn"
   :cases {:boundary [10 5 15] :past [10 5 16] :disabled [10 0 99] :future [20 5 10]}
   :gen (gen/tuple (gen/choose 0 1000) (gen/choose -1 100) (gen/choose 0 1200))
   :pred (fn [{[at ttl now] :in out :out}]
           (and (m/validate :boolean out) (= out (and (pos? ttl) (> (- now at) ttl)))))})
(defn wrap-observation [ttl]
  (let [delegate (Object.) wrapped (e/wrap delegate ttl (constantly 0))]
    {:in ttl :out {:same? (identical? delegate wrapped) :cache? (satisfies? cache/ITranscriptCache wrapped)}}))
(deftrifecta wrap wrap-observation
  {:golden-path "test/golden/adapters-expiring-wrap.edn"
   :cases {:disabled 0 :negative -1 :enabled 10}
   :gen (gen/choose -10 100)
   :pred (fn [{ttl :in {:keys [same? cache?] :as out} :out}]
           (and (m/validate [:map [:same? :boolean] [:cache? :boolean]] out)
                (= same? (not (pos? ttl))) (= cache? (pos? ttl))))})
(deftest offline-expiry-protocol
  (let [rows (atom {}) now (atom 10)
        delegate (reify cache/ITranscriptCache
                   (fetch [_ k] {:ok (get @rows k)})
                   (store! [_ k t] (swap! rows assoc k t) {:ok k})
                   (forget! [_ k] (swap! rows dissoc k) {:ok k})
                   (evict! [_ _] {:ok 0}))
        c (e/wrap delegate 5 #(deref now))]
    (is (m/validate p/CacheWriteResult (cache/store! c "k" transcript)))
    (is (= {:ok transcript} (cache/fetch c "k")))
    (is (m/validate p/CacheFetchResult (cache/fetch c "k")))
    (reset! now 16)
    (is (= {:ok nil} (cache/fetch c "k")))
    (is (empty? @rows))
    (is (m/validate p/CacheFetchResult (cache/fetch c "absent")))
    (is (m/validate p/CacheEvictResult (cache/evict! c 10)))
    (is (m/validate p/CacheWriteResult (cache/forget! c "k")))))
