(ns vtranslate.engine.addon-visibility-test
  "register-adapters! must REPORT a configured addon that did not come up.

   The loader has always returned a per-addon result carrying the reason; the
   caller dropped it, so an addon that never loaded was indistinguishable from
   one that loaded and had nothing to do. These tests pin both failure shapes
   and, most importantly, the one that `:loaded?` alone gets wrong."
  (:require [clojure.test :refer [deftest is testing]]
            [vtranslate.engine.main :as sut]))

;; --- addon-failure: the two shapes -------------------------------------------

(deftest a-healthy-addon-has-no-failure
  (is (nil? (sut/addon-failure {:addon/id :vtranslate/context
                                :loaded? true
                                :result {:ok :initialized}})))
  (testing "an addon whose init returns a plain value is not a failure either:
            :result only carries a Result when the addon is an IAddon"
    (is (nil? (sut/addon-failure {:addon/id :plain :loaded? true :result nil})))
    (is (nil? (sut/addon-failure {:addon/id :plain :loaded? true :result 42})))))

(deftest a-ns-that-could-not-be-required-is-a-failure
  (is (= :addon/load-failed
         (sut/addon-failure {:addon/ns 'x.y :loaded? false
                             :error :addon/load-failed
                             :message "boom"})))
  (is (= :addon/invalid-spec
         (sut/addon-failure {:loaded? false :error :addon/invalid-spec})))
  (is (= :addon/no-init
         (sut/addon-failure {:addon/ns 'x.y :loaded? false :error :addon/no-init}))))

(deftest an-addon-that-loaded-and-then-failed-its-own-init-is-a-failure
  ;; THE ONE `:loaded?` GETS WRONG. The ns required fine, so :loaded? is true;
  ;; the addon's own initialize! answered an err Result. Reading :loaded? alone
  ;; reports this as a success, which is precisely how a context addon that
  ;; could not initialize kept looking healthy.
  (is (= :addon/init-failed
         (sut/addon-failure {:addon/id :vtranslate/context
                             :loaded? true
                             :result {:error :addon/init-failed
                                      :message "no backend"}}))))

;; --- addon-failures: what a human is handed ----------------------------------

(deftest failures-carry-an-id-a-reason-and-a-message
  (let [rows (sut/addon-failures
              [{:addon/id :ok-one :loaded? true :result {:ok 1}}
               {:addon/id :broken :loaded? false
                :error :addon/load-failed :message "ClassNotFound"}
               {:addon/id :half-broken :loaded? true
                :result {:error :addon/init-failed :message "no store"}}])]
    (is (= 2 (count rows)) "a healthy addon contributes no row")
    (is (= [:broken :addon/load-failed "ClassNotFound"] (first rows)))
    (is (= [:half-broken :addon/init-failed "no store"] (second rows))
        "the message is read off the err Result when the top level has none")))

(deftest an-addon-with-no-id-is-still-nameable
  (let [[[id]] (sut/addon-failures [{:addon/ns 'a.b :loaded? false
                                     :error :addon/no-init}])]
    (is (= 'a.b id) "falls back to the ns so the log line still names something"))
  (let [[[id]] (sut/addon-failures [{:loaded? false :error :addon/invalid-spec}])]
    (is (= :unknown id) "and to a placeholder rather than printing nil")))

(deftest no-addons-is-not-a-failure
  (is (= [] (sut/addon-failures [])))
  (is (= [] (sut/addon-failures nil))))

;; --- register-adapters! surfaces it ------------------------------------------

(deftest register-adapters-reports-a-configured-addon-that-did-not-load
  (let [out (java.io.StringWriter.)
        result (binding [*err* out]
                 (sut/register-adapters!
                  {:addons [{:ns 'vtranslate.engine.--no-such-addon--}]}))]
    (is (vector? (:addons result))
        "the loader's per-addon results are returned, not dropped")
    (is (some (fn [[id reason]]
                (and (= 'vtranslate.engine.--no-such-addon-- id)
                     (= :addon/load-failed reason)))
              (:addon-failures result)))
    (is (re-find #"did not load" (str out))
        "and it reaches stderr, because nothing else reads the return value in
         the deployed worker")))

(deftest an-addon-id-absent-from-the-catalog-is-refused-by-name
  ;; Distinct from a ns that cannot be required: an unknown catalog id never
  ;; yields a ns to require at all, so it is refused as :addon/unknown-id, with
  ;; the id and the {:ns ...} form in the message. Worth pinning because a typo
  ;; in engine-config.yaml lands HERE, and the two reasons send a reader to
  ;; different places (the config vs the classpath).
  (let [out    (java.io.StringWriter.)
        result (binding [*err* out]
                 (sut/register-adapters!
                  {:addons [{:addon :vtranslate/--no-such-addon--}]}))]
    (is (some (fn [[_ reason]] (= :addon/unknown-id reason))
              (:addon-failures result)))
    (is (re-find #"did not load: :addon/unknown-id: unknown addon id :vtranslate/--no-such-addon--"
                 (str out))
        "the stderr line names the id it refused")))

(deftest the-default-addon-set-is-reported-too
  ;; register-adapters! with NO :addons key does not load zero addons: routing
  ;; supplies a default set. On a classpath without them that is a failure, and
  ;; the whole point of this change is that it is now VISIBLE rather than
  ;; dropped. Asserting the shape, not the membership, so adding or removing a
  ;; default addon does not break this test.
  (let [out (java.io.StringWriter.)
        result (binding [*err* out] (sut/register-adapters! {}))]
    (is (vector? (:addon-failures result)))
    (doseq [[id reason message] (:addon-failures result)]
      (is (some? id))
      (is (keyword? reason))
      (is (or (nil? message) (string? message))))
    (when (seq (:addon-failures result))
      (is (re-find #"did not load" (str out))
          "every failure in the returned vector also reached stderr"))))
