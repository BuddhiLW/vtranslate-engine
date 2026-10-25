(ns vtranslate.engine.port-contract-trifecta-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.schema.port-contract :as c]
            [vtranslate.engine.port.transcriber :as t]))

(defn route-observation [xs]
  (let [route (fn [tag] (fn [decode lang] (update (decode lang) :ok conj tag)))
        opts (-> {} (t/with-route (route 1)) (t/with-route (route 2)))
        impl (t/transcriber (fn [audio lang opts] (t/decoded opts (fn [_] {:ok audio}) lang)))]
    {:in xs :out (t/transcribe* impl xs "en" opts)}))
(deftrifecta route-composition route-observation
  {:golden-path "test/golden/port-contract-route.edn"
   :cases {:empty [] :existing [9]}
   :gen (gen/vector (gen/choose -100 100))
   :pred (fn [{:keys [in out]}]
           (and (m/validate c/RawResult out) (= (into in [2 1]) (:ok out))))})

(defn clean-observation [xs]
  {:in xs :out (t/cleaned (-> {} (t/with-clean (fn [v _] (conj v 1)))
                                              (t/with-clean (fn [v _] (conj v 2)))) xs)})
(deftrifecta clean-composition clean-observation
  {:golden-path "test/golden/port-contract-clean.edn"
   :cases {:empty [] :existing [9]}
   :gen (gen/vector (gen/choose -100 100))
   :pred (fn [{:keys [in out]}] (and (m/validate c/Raw out) (= (into in [2 1]) out)))})

(defn hooks-observation [live]
  (let [impl (reify t/IDeclaresHooks (hooks-honoured [_] live))]
    {:in live :out {:honoured (t/honoured impl)
                   :inert (t/inert-hooks impl (zipmap t/hook-keys (repeat true)))}}))
(deftrifecta hook-declarations hooks-observation
  {:golden-path "test/golden/port-contract-hooks.edn"
   :cases {:none #{} :route #{:asr/route-window} :all #{:asr/route-window :asr/clean}}
   :gen (gen/set (gen/elements t/hook-keys))
   :pred (fn [{:keys [in out]}]
           (and (m/validate c/HookKeys (:honoured out))
                (m/validate c/HookKeys (:inert out))
                (= in (:honoured out))
                (= (set t/hook-keys) (into in (:inert out)))
                (not-any? in (:inert out))))})

(deftest absent-hooks-and-error-short-circuit
  (is (= #{} (t/honoured (Object.))))
  (is (= [1] (t/cleaned {} [1])))
  (is (= {:error :error/asr-failed}
         (t/decoded {:asr/clean (fn [& _] (throw (Exception. "must not clean")))}
                    (fn [_] {:error :error/asr-failed}) "en"))))
