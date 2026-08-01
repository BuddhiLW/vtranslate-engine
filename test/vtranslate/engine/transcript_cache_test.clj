(ns vtranslate.engine.transcript-cache-test
  "ITranscriptCache contract + the fressian round-trip.

   The suite is written ONCE against the port and run against BOTH a stub and
   the real fressian-file adapter, so the stub cannot drift away from the thing
   it stands in for. Every filesystem test works inside a temp dir it created
   itself — never the real cache."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check.generators :as gen]
            [babashka.fs :as fs]
            [hive-dsl.result :as r]
            [hive-schemas.test :refer [deftrifecta-from-schema]]
            [vtranslate.engine.adapters.transcript-cache.fressian-file :as fressian]
            [vtranslate.engine.calc.cache-key :as ck]
            [vtranslate.engine.port.transcript-cache :as port]))

(def ^:dynamic *dir* nil)

(defn- with-temp-dir [f]
  (let [d (fs/create-temp-dir {:prefix "vt-cache-test-"})]
    (try (binding [*dir* (str d)] (f))
         (finally (fs/delete-tree d)))))

(use-fixtures :each with-temp-dir)

;; ---------------------------------------------------------------------------
;; The pure key — free coverage from the schema
;; ---------------------------------------------------------------------------

(def KeyInputs
  [:map
   [:content-sha {:optional true} [:maybe :string]]
   [:provider    {:optional true} [:maybe :keyword]]
   [:model       {:optional true} [:maybe :string]]
   [:language    {:optional true} [:maybe :string]]
   [:segmenter   {:optional true} [:maybe :keyword]]
   [:span-pad-ms {:optional true} [:maybe :int]]])

(def Sha256Hex [:re #"^[0-9a-f]{64}$"])

(deftrifecta-from-schema transcript-key ck/transcript-key
  {:in KeyInputs
   :out Sha256Hex
   :rel (fn [_in out] (= 64 (count out)))
   :mutation false
   :num-tests 200})

(deftest cache-file-name-appends-the-fressian-extension
  (testing "total over keys — there is no input for which it does not hold"
    (doseq [k (gen/sample gen/string-alphanumeric 50)]
      (is (= (str k ".fressian") (ck/cache-file-name k))))))

(deftest the-key-is-deterministic-and-input-sensitive
  (let [base {:content-sha "abc" :provider :whisper-local :model "m.bin"
              :language "en" :segmenter :silero-vad :span-pad-ms 500}]
    (testing "same inputs => same key, every time"
      (is (= (ck/transcript-key base) (ck/transcript-key base))))

    (testing "each field participates — changing any one must miss"
      (doseq [[k v] {:content-sha "def" :provider :other :model "n.bin"
                     :language "pt" :segmenter :grid :span-pad-ms 250}]
        (is (not= (ck/transcript-key base) (ck/transcript-key (assoc base k v)))
            (str "changing " k " must change the key"))))

    (testing "an absent field is not the same as a present one"
      (is (not= (ck/transcript-key (dissoc base :model))
                (ck/transcript-key base))))

    (testing "the path is deliberately NOT part of the key"
      (is (= (ck/transcript-key base)
             (ck/transcript-key (assoc base :source "/somewhere/else.mp4")))
          "a renamed or moved video must still hit"))))

(deftest file-sha-identifies-content-not-name
  (let [a (str (fs/path *dir* "a.bin"))
        b (str (fs/path *dir* "b.bin"))
        c (str (fs/path *dir* "c.bin"))]
    (spit a "same bytes")
    (spit b "same bytes")
    (spit c "other bytes")
    (is (= (ck/file-sha a) (ck/file-sha b)) "identical content => identical sha")
    (is (not= (ck/file-sha a) (ck/file-sha c)))
    (is (re-find #"^[0-9a-f]{64}$" (ck/file-sha a)))
    (is (nil? (ck/file-sha (str (fs/path *dir* "missing.bin"))))
        "an unreadable file yields nil rather than throwing")))

;; ---------------------------------------------------------------------------
;; The port contract — run against every implementation
;; ---------------------------------------------------------------------------

(defrecord StubCache [store]
  port/ITranscriptCache
  (fetch  [_ key] (r/ok (get @store key)))
  (store! [_ key transcript] (swap! store assoc key transcript) (r/ok key)))

(defn- implementations
  "Every ITranscriptCache the contract must hold for."
  []
  {:stub    (->StubCache (atom {}))
   :fressian (fressian/make-cache *dir*)})

(def sample-transcript
  "Shaped like a real Transcript: nested maps, keyword keys, vectors, strings
   with non-ASCII, longs and doubles — everything fressian has to survive."
  {:id "j1-tx"
   :asset-id "j1-asset"
   :language "en"
   :segments [{:index 1 :start-ms 0 :end-ms 1500 :text "Olá, mundo — ação!"
               :confidence 0.93 :language "pt-BR"}
              {:index 2 :start-ms 1500 :end-ms 3200 :text "Привет, мир"
               :confidence 0.81 :language "ru"}]})

(deftest every-cache-misses-before-it-is-written
  (doseq [[label cache] (implementations)]
    (testing (name label)
      (let [res (port/fetch cache "no-such-key")]
        (is (r/ok? res))
        (is (nil? (:ok res)) "a miss is (r/ok nil), not an error")))))

(deftest every-cache-round-trips-a-transcript
  (doseq [[label cache] (implementations)]
    (testing (name label)
      (let [k "key-round-trip"]
        (is (r/ok? (port/store! cache k sample-transcript)))
        (let [res (port/fetch cache k)]
          (is (r/ok? res))
          (is (= sample-transcript (:ok res))
              "what comes back must equal what went in, exactly"))))))

(deftest every-cache-keeps-entries-apart
  (doseq [[label cache] (implementations)]
    (testing (name label)
      (port/store! cache "k1" (assoc sample-transcript :id "one"))
      (port/store! cache "k2" (assoc sample-transcript :id "two"))
      (is (= "one" (get-in (port/fetch cache "k1") [:ok :id])))
      (is (= "two" (get-in (port/fetch cache "k2") [:ok :id]))))))

(deftest every-cache-overwrites-on-restore
  (doseq [[label cache] (implementations)]
    (testing (name label)
      (port/store! cache "k" (assoc sample-transcript :id "first"))
      (port/store! cache "k" (assoc sample-transcript :id "second"))
      (is (= "second" (get-in (port/fetch cache "k") [:ok :id]))))))

(deftest the-disabled-cache-never-hits-but-never-errors
  (is (port/cache? port/disabled))
  (is (r/ok? (port/store! port/disabled "k" sample-transcript)))
  (is (nil? (:ok (port/fetch port/disabled "k")))
      "storing into the disabled cache must not make it start hitting"))

;; ---------------------------------------------------------------------------
;; Fressian specifics — what the stub cannot tell us
;; ---------------------------------------------------------------------------

(deftest fressian-writes-a-real-file-under-the-cache-dir
  (let [cache (fressian/make-cache *dir*)
        k     (ck/transcript-key {:content-sha "abc"})]
    (port/store! cache k sample-transcript)
    (let [path (str (fs/path *dir* (ck/cache-file-name k)))]
      (is (fs/regular-file? path))
      (is (pos? (fs/size path)))
      (testing "and it is binary fressian, not EDN text"
        (is (not (re-find #"^\{:id" (slurp path))))))))

(deftest fressian-survives-a-fresh-cache-instance
  (let [k "persisted"]
    (port/store! (fressian/make-cache *dir*) k sample-transcript)
    (testing "a NEW cache object reads what a previous one wrote — the whole point"
      (is (= sample-transcript
             (:ok (port/fetch (fressian/make-cache *dir*) k)))))))

(deftest fressian-round-trips-generated-transcripts
  (let [cache (fressian/make-cache *dir*)]
    (doseq [[i t] (map-indexed vector
                               (gen/sample
                                (gen/hash-map
                                 :id gen/string-alphanumeric
                                 :language gen/string-alphanumeric
                                 :segments (gen/vector
                                            (gen/hash-map
                                             :index gen/nat
                                             :start-ms gen/nat
                                             :end-ms gen/nat
                                             :text gen/string
                                             :confidence (gen/double* {:infinite? false :NaN? false}))
                                            0 5))
                                25))]
      (let [k (str "gen-" i)]
        (port/store! cache k t)
        (is (= t (:ok (port/fetch cache k)))
            (str "round-trip failed for generated transcript " i))))))

(deftest fressian-creates-a-missing-cache-directory
  (let [nested (str (fs/path *dir* "deep" "nested"))
        cache  (fressian/make-cache nested)]
    (is (r/ok? (port/store! cache "k" sample-transcript)))
    (is (fs/directory? nested) "the adapter creates its own directory")
    (is (= sample-transcript (:ok (port/fetch cache "k"))))))
