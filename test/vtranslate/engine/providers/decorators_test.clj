(ns vtranslate.engine.providers.decorators-test
  "Port decorators several addons contribute: ordering, replacement by id,
   failure, cache identities, and wiring through build-port."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [hive-dsl.result :as r]
            [vtranslate.engine.port.translator :as p.tr]
            [vtranslate.engine.providers.decorators :as sut]))

(def ^:private ids [::inner ::outer ::failing ::x])

(use-fixtures :each
  (fn [f]
    (try (f)
         (finally (doseq [id ids] (sut/unregister! ::port id))))))

(defn- tagging [tag]
  (fn [inner _config]
    (p.tr/translator
     (fn [texts s t opts]
       (r/let-ok [out (p.tr/translate inner texts s t opts)]
         (r/ok (mapv #(str tag %) out)))))))

(def ^:private base (p.tr/translator (fn [texts _ _ _] (r/ok (vec texts)))))

(deftest the-lowest-order-sits-innermost
  (sut/register! ::port ::outer {:order 20 :wrap (tagging "o")})
  (sut/register! ::port ::inner {:order 10 :wrap (tagging "i")})
  (let [t (:ok (sut/decorate ::port base {}))]
    (is (= ["oia"] (:ok (p.tr/translate t ["a"] "en" "pt" {}))))))

(deftest registering-an-id-again-replaces-it
  (sut/register! ::port ::x {:wrap (tagging "1")})
  (sut/register! ::port ::x {:wrap (tagging "2")})
  (is (= 1 (count (sut/contributions ::port))))
  (is (= ["2a"] (:ok (p.tr/translate (:ok (sut/decorate ::port base {})) ["a"] "en" "pt" {})))))

(deftest a-decorator-that-fails-fails-the-build
  (sut/register! ::port ::failing {:wrap (fn [_ _] (r/err :error/boom {}))})
  (is (= :error/boom (:error (sut/decorate ::port base {})))))

(deftest no-contribution-leaves-the-port-as-it-is
  (is (identical? base (:ok (sut/decorate ::port base {})))))

(deftest identities-name-the-active-decorators
  (sut/register! ::port ::inner {:wrap (tagging "i") :identity (fn [c] (when (:on c) "v1"))})
  (sut/register! ::port ::outer {:wrap (tagging "o")})
  (is (= [] (sut/identities ::port {})) "inactive or identity-less decorators name nothing")
  (is (= [(str ::inner "=v1")] (sut/identities ::port {:on true}))))
