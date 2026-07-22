(ns vtranslate.engine.addons-test
  "Addon loader FAILURE contract (:invalid-spec / :no-init / :load-failed) +
   normalize-spec branch coverage. The success + catalog paths live in
   addons-catalog-test."
  (:require [clojure.test :refer [deftest is]]
            [vtranslate.engine.addons :as sut]))

;; --- normalize-spec branches -------------------------------------------------

(deftest normalize-spec-symbol-and-string
  (is (= {:ns 'my.addon :config {}} (sut/normalize-spec 'my.addon)))
  (is (= {:ns 'my.addon :config {}} (sut/normalize-spec "my.addon"))))

(deftest normalize-spec-plain-map-without-catalog
  (let [n (sut/normalize-spec {:ns 'x.y :config {:k 1}})]
    (is (= 'x.y (:ns n)))
    (is (= {:k 1} (:config n)))))

(deftest normalize-spec-invalid-yields-nil-ns
  (let [n (sut/normalize-spec 42)]
    (is (nil? (:ns n)))
    (is (= 42 (:invalid n)))))

;; --- load-addon! failure contract --------------------------------------------

(deftest load-addon-invalid-spec
  (let [res (sut/load-addon! 42)]
    (is (false? (:loaded? res)))
    (is (= :addon/invalid-spec (:error res)))))

(deftest load-addon-no-init
  ;; a ns that loads fine but exposes no init-as-addon!/register-adapters! fn
  (let [res (sut/load-addon! {:ns 'vtranslate.engine.collect.units})]
    (is (false? (:loaded? res)))
    (is (= :addon/no-init (:error res)))))

(deftest load-addon-load-failed
  ;; a ns that cannot be required funnels into :addon/load-failed (never a throw)
  (let [res (sut/load-addon! {:ns 'vtranslate.engine.--no-such-addon--})]
    (is (false? (:loaded? res)))
    (is (= :addon/load-failed (:error res)))
    (is (string? (:message res)))))

;; --- load-addons! maps over specs, tolerates nil -----------------------------

(deftest load-addons-tolerates-nil-and-maps-each
  (is (= [] (sut/load-addons! nil)))
  (is (= 1 (count (sut/load-addons! [42])))))
