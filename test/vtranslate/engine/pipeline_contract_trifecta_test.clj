(ns vtranslate.engine.pipeline-contract-trifecta-test
  (:require [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [malli.core :as m]
            [vtranslate.engine.schema.pipeline-contract :as c]
            [vtranslate.engine.pipeline.fsm :as f]
            [vtranslate.engine.pipeline.extensions :as e]))

(defn result-observation [[error? x]]
  (let [result (if error? {:error :error/pinned :value x} {:ok x})
        state (f/result-state result)]
    {:in result :out {:state state :result (f/result-of state)
                     :continue (f/continue? state) :halted (f/halted? state)}}))
(deftrifecta result-state-guards result-observation
  {:golden-path "test/golden/pipeline-contract-result.edn"
   :cases {:ok [false 2] :error [true 5]}
   :gen (gen/tuple gen/boolean gen/int)
   :pred (fn [{:keys [in out]}]
           (and (m/validate c/State (:state out)) (m/validate c/Result (:result out))
                (= in (:result out) (get-in out [:state :result]))
                (= (contains? in :ok) (:continue out))
                (= (contains? in :error) (:halted out))
                (not= (:continue out) (:halted out))))})

(defn with-result-observation [[error? xs]]
  (let [calls (atom 0) result (if error? {:error :error/pinned} {:ok xs})]
    {:in {:error? error? :xs xs :result result}
     :out {:state (f/with-result (f/result-state result)
                   (fn [v] (swap! calls inc) {:ok (conj v :next)}))
           :calls @calls}}))
(deftrifecta with-result-short-circuit with-result-observation
  {:golden-path "test/golden/pipeline-contract-with-result.edn"
   :cases {:ok [false []] :error [true [1]]}
   :gen (gen/tuple gen/boolean (gen/vector gen/int))
   :pred (fn [{:keys [in out]}]
           (and (m/validate c/State (:state out))
                (= (if (:error? in) 0 1) (:calls out))
                (= (if (:error? in) (:result in) {:ok (conj (:xs in) :next)})
                   (get-in out [:state :result]))))})

(defn stage-observation [[id x]]
  (let [handler (fn [resource state] {:resource resource :state state})
        stage (f/stage id handler)
        stub (reify f/IJobStage (stage-id [_] id) (apply-stage [_ r s] (handler r s)))]
    {:in [id x] :out {:id (f/stage-id stage)
                     :actual (f/apply-stage stage :resource x)
                     :stub (f/apply-stage stub :resource x)}}))
(deftrifecta stage-protocol stage-observation
  {:golden-path "test/golden/pipeline-contract-stage.edn"
   :cases {:start [:start 0] :named [:named 7]} :gen (gen/tuple gen/keyword gen/int)
   :pred (fn [{:keys [in out]}]
           (and (m/validate [:map [:id c/StageId] [:actual :map] [:stub :map]] out)
                (= (first in) (:id out))
                (= {:resource :resource :state (second in)} (:actual out) (:stub out))))})

(defn pipeline-observation [[n fail-at]]
  (let [trace (atom [])
        stages (mapv (fn [i]
                       (f/stage (if (zero? i) f/start-id (keyword (str "s" i)))
                                (fn [resources state]
                                  (let [state (if (zero? i) (f/result-state {:ok (:data state)}) state)]
                                    (f/with-result state
                                      (fn [ctx]
                                        (swap! trace conj i)
                                        (if (= i fail-at) {:error :error/pinned :at i}
                                            {:ok (conj (vec ctx) (+ i (:offset resources)))})))))))
                     (range n))
        compiled (f/compile-stages stages)
        pipeline (f/pipeline {:offset 10} compiled)
        result (f/run-pipeline pipeline [])
        stub (reify f/IPipeline (run-pipeline [_ spec] {:ok spec}))]
    {:in [n fail-at] :out {:trace @trace :result result :stub (f/run-pipeline stub [n])}}))
(deftrifecta compile-and-run pipeline-observation
  {:golden-path "test/golden/pipeline-contract-run.edn"
   :cases {:one [1 -1] :all [4 -1] :first-error [4 0] :middle-error [4 2]}
   :gen (gen/tuple (gen/choose 1 8) (gen/choose -1 8))
   :pred (fn [{:keys [in out]}]
           (let [[n fail] in hit? (< -1 fail n) count-run (if hit? (inc fail) n)]
             (and (m/validate [:map [:trace c/Trace] [:result c/Result] [:stub c/Result]] out)
                  (= (vec (range count-run)) (:trace out))
                  (= {:ok [n]} (:stub out))
                  (= (if hit? {:error :error/pinned :at fail} {:ok (mapv #(+ 10 %) (range n))})
                     (:result out)))))})

(defn extensions-observation [orders]
  (let [phase ::isolated ids (mapv #(keyword (str "m" %)) (range (count orders)))
        expected (mapv first (sort-by (fn [[id order]] [order (str id)]) (map vector ids orders)))
        mf e/middleware old (get-method mf phase) had? (contains? (methods mf) phase)]
    (try
      (.addMethod ^clojure.lang.MultiFn mf phase (fn [_ resource] [(fn [_ ctx] (conj ctx resource))]))
      (doseq [[id order] (map vector ids orders)]
        (e/contribute! phase id (fn [_ ctx] (conj ctx id)) order))
      (let [run (fn [mws] (reduce (fn [ctx mw] (mw {} ctx)) [] mws))]
        {:in {:orders orders :expected expected}
         :out {:contributed (run (e/contributed phase))
               :phase (run (e/phase-middleware phase :base))
               :default (e/middleware ::missing {})}})
      (finally
        (doseq [id ids] (e/retract! phase id))
        (if had? (.addMethod ^clojure.lang.MultiFn mf phase old) (remove-method mf phase))))))
(deftrifecta extension-order extensions-observation
  {:golden-path "test/golden/pipeline-contract-extensions.edn"
   :cases {:none [] :ties [2 0 0] :reverse [3 2 1]}
   :gen (gen/vector (gen/choose -5 5) 0 10)
   :pred (fn [{:keys [in out]}]
           (and (m/validate [:map [:contributed c/MiddlewareIds] [:phase c/MiddlewareIds] [:default c/MiddlewareIds]] out)
                (= (:expected in) (:contributed out))
                (= (into [:base] (:expected in)) (:phase out))
                (= [] (:default out))))})
