(ns vtranslate.engine.residency-test
  (:require [clojure.test :refer [deftest is testing]]
            [hive-dsl.result :as r]
            [vtranslate.engine.port.model-host :as p.host]
            [vtranslate.engine.residency :as res]))

(defrecord StubHost [state]
  p.host/IModelHost
  (loaded [_] (r/ok (:loaded @state)))
  (load! [_ id]
    (swap! state #(-> % (update :loaded conj id) (update :calls conj [:load id])))
    (r/ok id))
  (unload! [_ id]
    (swap! state update :calls conj [:unload id])
    (cond
      (contains? (:busy @state) id)        (r/ok :busy)
      (not (contains? (:loaded @state) id)) (r/ok :absent)
      :else (do (swap! state update :loaded disj id) (r/ok :unloaded)))))

(defn- stub-host
  ([loaded] (stub-host loaded #{}))
  ([loaded busy] (->StubHost (atom {:loaded (set loaded) :busy (set busy) :calls []}))))

(defn- calls [h] (:calls @(:state h)))

(def ^:private three-fit
  "Three 1000 MiB models fit a 3500 MiB budget; a fourth does not."
  {:budget-mib 3500 :default-model-mib 1000})

(deftest a-model-already-resident-is-used-as-it-is
  (let [h (stub-host #{"a"})]
    (is (= {:model "a" :evicted []} (:ok (res/ensure! (res/owner h three-fit) "a"))))
    (is (= [] (calls h)))))

(deftest a-model-that-fits-is-loaded-without-unloading-anything
  (let [h (stub-host #{"a"})]
    (is (= {:model "b" :evicted []} (:ok (res/ensure! (res/owner h three-fit) "b"))))
    (is (= [[:load "b"]] (calls h)))))

(deftest the-least-recently-used-model-makes-room
  (let [h (stub-host #{})
        o (res/owner h three-fit)]
    (doseq [id ["a" "b" "c" "a"]] (res/ensure! o id))
    (is (= {:model "d" :evicted ["b"]} (:ok (res/ensure! o "d"))))
    (is (= #{"a" "c" "d"} (:loaded @(:state h))))))

(deftest a-model-a-decode-holds-is-skipped-for-the-next-one
  (let [h (stub-host #{} #{"b"})
        o (res/owner h three-fit)]
    (doseq [id ["b" "c" "a"]] (res/ensure! o id))
    (is (= {:model "d" :evicted ["c"]} (:ok (res/ensure! o "d"))))
    (is (= [[:unload "b"] [:unload "c"] [:load "d"]] (take-last 3 (calls h))))))

(deftest models-another-process-loaded-go-first
  (testing "a model the ledger never saw has no known last use, so it is evicted first"
    (let [h (stub-host #{})
          o (res/owner h three-fit)]
      (doseq [id ["a" "b"]] (res/ensure! o id))
      (swap! (:state h) update :loaded conj "stranger")
      (is (= ["stranger"] (:evicted (:ok (res/ensure! o "c"))))))))

(deftest a-model-bigger-than-the-device-is-refused-before-anything-is-unloaded
  (let [h (stub-host #{"a"})
        o (res/owner h (assoc three-fit :model-mib {"huge" 4000}))
        e (res/ensure! o "huge")]
    (is (r/err? e))
    (is (= :budget/model-exceeds-device (get-in e [:error :reason] (:reason e))))
    (is (= [] (calls h)))))

(deftest every-model-busy-is-a-refusal-not-a-load
  (let [h (stub-host #{} #{"a" "b" "c"})
        o (res/owner h three-fit)]
    (doseq [id ["a" "b" "c"]] (res/ensure! o id))
    (is (r/err? (res/ensure! o "d")))
    (is (not (contains? (:loaded @(:state h)) "d")))))

(deftest no-budget-means-no-ceiling
  (let [h (stub-host #{"a" "b" "c" "d"})]
    (is (= [] (:evicted (:ok (res/ensure! (res/owner h {}) "e")))))
    (is (= [[:load "e"]] (calls h)))))

(deftest one-owner-per-server-and-policy
  (let [made (atom 0)
        mk   #(do (swap! made inc) (stub-host #{}))
        url  (str "http://" (gensym "server-"))]
    (is (identical? (res/shared-owner url three-fit mk)
                    (res/shared-owner url three-fit mk)))
    (is (= 1 @made))))
